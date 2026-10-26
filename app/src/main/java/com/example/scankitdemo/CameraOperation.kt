package com.example.scankitdemo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import com.huawei.hms.scankit.ScanPlusConfig
import com.huawei.hms.scankit.util.ImageConversionUtils
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Modern Camera2 implementation of [CameraOperation] in Kotlin.
 * Replaces the legacy Camera1 API for MultiProcessor scanning in [CommonActivity].
 * Supports TextureView preview with proper CenterCrop transform and aspect ratio preservation.
 */
class CameraOperation {
    companion object {
        private const val TAG = "CameraOperation"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
    }

    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var isPreview = false
    private val defaultZoom = 1.0

    private var previewWidth = PREVIEW_WIDTH
    private var previewHeight = PREVIEW_HEIGHT
    private var pendingZoom = defaultZoom

    private val targetHandlerRef = AtomicReference<Handler?>()
    private val cameraOpenCloseLock = Semaphore(1)

    private var maxZoomRatio: Float = 1.0f
    private var sensorActiveArraySize: Rect? = null
    private var sensorOrientation: Int = 90

    private var textureView: TextureView? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var frameBuffer: ByteArray? = null

    fun getRotationDegrees(): Int {
        val ctx = textureView?.context ?: return 0
        return com.huawei.hms.scankit.camera.CameraOrientationUtils.getEffectiveOrientation(
            context = ctx,
            sensorOrientation = sensorOrientation,
            isFront = false,
            fallbackDisplayOrientation = 90
        )
    }

    fun getEffectiveWidth(): Int {
        val rotationDegrees = getRotationDegrees()
        val isBufferSwapped = (rotationDegrees == 90 || rotationDegrees == 270)
        return if (isBufferSwapped) previewHeight else previewWidth
    }

    fun getEffectiveHeight(): Int {
        val rotationDegrees = getRotationDegrees()
        val isBufferSwapped = (rotationDegrees == 90 || rotationDegrees == 270)
        return if (isBufferSwapped) previewWidth else previewHeight
    }

    /**
     * Opens the camera using Camera2 and attaches to the TextureView.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun open(view: TextureView) {
        this.textureView = view
        this.surfaceHolder = null
        startBackgroundThread()

        val cm = getCameraManager(view.context)
        openCameraInternal(cm) {
            createSessionForTextureView(view)
        }
    }

    /**
     * Opens the camera using Camera2 and attaches to the SurfaceHolder (compatibility).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun open(holder: SurfaceHolder?) {
        if (holder == null) return
        this.surfaceHolder = holder
        this.textureView = null
        startBackgroundThread()

        val cm = (cameraManager ?: run {
            val appClass = Class.forName("android.app.ActivityThread")
            val appMethod = appClass.getMethod("currentApplication")
            val app = appMethod.invoke(null) as Context
            getCameraManager(app)
        })

        openCameraInternal(cm) {
            createSessionForSurfaceHolder(holder)
        }
    }

    private fun getCameraManager(context: Context): CameraManager {
        return (cameraManager ?: (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).also {
            cameraManager = it
        })
    }

    @SuppressLint("MissingPermission")
    private fun openCameraInternal(cm: CameraManager, onOpenedAction: () -> Unit) {
        try {
            if (cameraId == null) {
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id
                        cameraCharacteristics = chars
                        initCharacteristics(chars)
                        break
                    }
                }
                if (cameraId == null && cm.cameraIdList.isNotEmpty()) {
                    val fallbackId = cm.cameraIdList[0]
                    cameraId = fallbackId
                    val chars = cm.getCameraCharacteristics(fallbackId)
                    cameraCharacteristics = chars
                    initCharacteristics(chars)
                }
            }

            val id = cameraId ?: return

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    onOpenedAction()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera open error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open Camera2", e)
        }
    }

    private fun initCharacteristics(chars: CameraCharacteristics) {
        sensorActiveArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            maxZoomRatio = range?.upper ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        } else {
            maxZoomRatio = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        }
        selectOptimalSize(chars)
    }

    private fun selectOptimalSize(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: return
        val surfaceSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val choices = yuvSizes.filter { surfaceSizes.contains(it) }.ifEmpty { yuvSizes }
        if (choices.isEmpty()) return

        val targetRatio = 16.0 / 9.0
        val exactMatch = choices.firstOrNull { it.width == PREVIEW_WIDTH && it.height == PREVIEW_HEIGHT }
        if (exactMatch != null) {
            previewWidth = exactMatch.width
            previewHeight = exactMatch.height
            Log.i(TAG, "Selected exact preview size: $previewWidth x $previewHeight")
            return
        }

        var bestSize: Size? = null
        var minDiff = Double.MAX_VALUE
        var bestDistToTarget = Int.MAX_VALUE

        val sorted = choices.sortedWith(compareByDescending { it.width.toLong() * it.height.toLong() })
        for (size in sorted) {
            val w = size.width
            val h = size.height
            if (w.toLong() * h.toLong() < 153600L) continue

            val ratio = maxOf(w, h).toDouble() / minOf(w, h).toDouble().coerceAtLeast(1.0)
            val diff = abs(ratio - targetRatio)
            val dist = abs(w - PREVIEW_WIDTH) + abs(h - PREVIEW_HEIGHT)

            if (diff < minDiff - 0.01) {
                minDiff = diff
                bestDistToTarget = dist
                bestSize = size
            } else if (abs(diff - minDiff) <= 0.01) {
                if (dist < bestDistToTarget) {
                    bestDistToTarget = dist
                    bestSize = size
                }
            }
        }

        val chosen = bestSize ?: sorted[0]
        previewWidth = chosen.width
        previewHeight = chosen.height
        Log.i(TAG, "Selected optimal preview size: $previewWidth x $previewHeight")
    }

    fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val view = textureView ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        if (previewWidth == 0 || previewHeight == 0) return

        val rotationDegrees = getRotationDegrees()

        val matrix = Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        if (rotationDegrees == 90 || rotationDegrees == 270) {
            val rotatedW = previewHeight.toFloat()
            val rotatedH = previewWidth.toFloat()
            val scale = maxOf(viewWidth.toFloat() / rotatedW, viewHeight.toFloat() / rotatedH)

            val scaleX = scale * previewHeight / viewHeight
            val scaleY = scale * previewWidth / viewWidth

            matrix.postRotate(rotationDegrees.toFloat(), centerX, centerY)
            matrix.postScale(scaleX, scaleY, centerX, centerY)
        } else {
            val rotatedW = previewWidth.toFloat()
            val rotatedH = previewHeight.toFloat()
            val scale = maxOf(viewWidth.toFloat() / rotatedW, viewHeight.toFloat() / rotatedH)

            val scaleX = scale * previewWidth / viewWidth
            val scaleY = scale * previewHeight / viewHeight

            if (rotationDegrees == 180) {
                matrix.postRotate(180f, centerX, centerY)
            }
            matrix.postScale(scaleX, scaleY, centerX, centerY)
        }

        Log.i(TAG, "configureTransform: view=${viewWidth}x${viewHeight}, preview=${previewWidth}x${previewHeight}, rotationDegrees=$rotationDegrees")

        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.setTransform(matrix)
        } else {
            Handler(Looper.getMainLooper()).post {
                view.setTransform(matrix)
            }
        }
    }

    private fun createSessionForTextureView(view: TextureView) {
        val texture = view.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewWidth, previewHeight)
        val surface = Surface(texture)
        previewSurface = surface

        if (view.width > 0 && view.height > 0) {
            configureTransform(view.width, view.height)
        }

        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != (or - ol) || h != (ob - ot))) {
                configureTransform(w, h)
            }
        }

        createSessionInternal(surface)
    }

    private fun createSessionForSurfaceHolder(holder: SurfaceHolder) {
        val surface = holder.surface
        if (!surface.isValid) {
            Log.w(TAG, "Surface is not valid yet")
            return
        }
        previewSurface = surface
        createSessionInternal(surface)
    }

    private fun createSessionInternal(previewSurface: Surface) {
        val device = cameraDevice ?: return
        try {
            imageReader?.close()
            val reader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 3)
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val handler = targetHandlerRef.getAndSet(null)
                try {
                    if (handler != null) {
                        val requiredSize = image.width * image.height * 3 / 2
                        if (frameBuffer == null || frameBuffer!!.size != requiredSize) {
                            frameBuffer = ByteArray(requiredSize)
                        }
                        val nv21 = ImageConversionUtils.yuv420888ToNv21(image, frameBuffer)
                        val message = Message.obtain(handler, 0, image.width, image.height, nv21)
                        message.sendToTarget()
                    }
                } catch (e: IllegalStateException) {
                    // Closed concurrently
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image callback", e)
                } finally {
                    try {
                        image.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }, backgroundHandler)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        startRepeating()
                        textureView?.let { tv ->
                            tv.post {
                                if (tv.width > 0 && tv.height > 0) {
                                    configureTransform(tv.width, tv.height)
                                }
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Configure failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "createSession error", e)
        }
    }

    private fun startRepeating() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            if (pendingZoom != defaultZoom) {
                applyZoom(builder, pendingZoom.toFloat())
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            isPreview = true
        } catch (e: Exception) {
            Log.e(TAG, "startRepeating error", e)
        }
    }

    @Synchronized
    fun startPreview() {
        if (!isPreview && captureSession != null) {
            startRepeating()
        }
    }

    @Synchronized
    fun stopPreview() {
        if (isPreview) {
            try {
                captureSession?.stopRepeating()
                isPreview = false
            } catch (e: Exception) {
                Log.e(TAG, "stopPreview error", e)
            }
        }
    }

    @Synchronized
    fun close() {
        try {
            cameraOpenCloseLock.acquire()
            stopPreview()
            targetHandlerRef.set(null)
            pendingZoom = defaultZoom
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            previewSurface?.release()
            previewSurface = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
        }
    }

    @Synchronized
    fun callbackFrame(handler: Handler?, zoomValue: Double) {
        if (handler == null) return
        targetHandlerRef.set(handler)
        if (zoomValue != defaultZoom) {
            pendingZoom = zoomValue
            if (cameraDevice != null && isPreview) {
                setZoom(zoomValue.toFloat())
            }
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoomValue: Float) {
        val clamped = zoomValue.coerceIn(1.0f, maxZoomRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            cameraCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
        ) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
        } else {
            val arraySize = sensorActiveArraySize ?: return
            val cropW = (arraySize.width() / clamped).toInt()
            val cropH = (arraySize.height() / clamped).toInt()
            val left = (arraySize.width() - cropW) / 2
            val top = (arraySize.height() - cropH) / 2
            val cropRect = Rect(left, top, left + cropW, top + cropH)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
        }
    }

    private fun setZoom(zoomValue: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            applyZoom(builder, zoomValue)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom", e)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraOperationBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted stopping thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }
}
