package com.huawei.hms.scankit.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.huawei.hms.scankit.util.ImageConversionUtils
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Modern Camera2 implementation replacing legacy Camera1 API.
 * Encapsulates camera lifecycle, preview rendering, frame streaming,
 * tap-to-focus, zoom, and torch control.
 */
class Camera2Manager(
    private val context: Context,
    private val lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
) {
    companion object {
        private const val TAG = "ScanKitCamera2"
        private const val DEFAULT_PREVIEW_WIDTH = 1920
        private const val DEFAULT_PREVIEW_HEIGHT = 1080
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Background Thread
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Camera Hardware Objects
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    // Surfaces & Readers
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Point = Point(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
    private var sensorOrientation = 90
    private var sensorActiveArraySize: Rect? = null

    // Zoom & Exposure
    private var maxZoomRatio: Float = 1.0f
    private var minZoomRatio: Float = 1.0f
    private var currentZoomRatio: Float = 1.0f
    private var currentZoomIndex: Int = 0
    private var zoomRatios: List<Int> = listOf(100)
    private var exposureRange: Range<Int> = Range(0, 0)
    private var exposureStep: Float = 1.0f
    private var currentExposure: Int = 0

    // Torch & Status
    private var isTorchOn: Boolean = false
    private var isPreviewStarted: Boolean = false
    private val cameraOpenCloseLock = Semaphore(1)

    // Preallocated Frame Buffer
    private var frameBuffer: ByteArray? = null

    // Listeners
    var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null
    var onCameraOpened: (() -> Unit)? = null
    var onCameraClosed: (() -> Unit)? = null
    var onCameraError: ((Throwable) -> Unit)? = null

    init {
        findCameraId()
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("ScanKitCamera2Background").apply {
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
            Log.e(TAG, "Error stopping background thread", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun findCameraId() {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == lensFacing) {
                    cameraId = id
                    cameraCharacteristics = characteristics
                    initCharacteristics(characteristics)
                    break
                }
            }
            if (cameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                val fallbackId = cameraManager.cameraIdList[0]
                cameraId = fallbackId
                val characteristics = cameraManager.getCameraCharacteristics(fallbackId)
                cameraCharacteristics = characteristics
                initCharacteristics(characteristics)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect camera characteristics", e)
        }
    }

    private fun initCharacteristics(characteristics: CameraCharacteristics) {
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        sensorActiveArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        // Zoom capabilities
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                minZoomRatio = range.lower
                maxZoomRatio = range.upper
            } else {
                maxZoomRatio = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                minZoomRatio = 1.0f
            }
        } else {
            maxZoomRatio = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
            minZoomRatio = 1.0f
        }

        // Build 100-step zoom list (100 -> maxZoom * 100)
        val ratios = mutableListOf<Int>()
        val maxPercent = (maxZoomRatio * 100).toInt().coerceAtLeast(100)
        val step = ((maxPercent - 100) / 100).coerceAtLeast(1)
        for (v in 100..maxPercent step step) {
            ratios.add(v)
        }
        if (!ratios.contains(maxPercent)) {
            ratios.add(maxPercent)
        }
        zoomRatios = ratios

        // Exposure capabilities
        exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val stepRational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        exposureStep = stepRational?.toFloat() ?: 1.0f
    }

    /**
     * Connects preview TextureView and initializes camera preview size.
     */
    fun attachTextureView(view: TextureView) {
        this.textureView = view
        selectOptimalSize(view.width, view.height)
    }

    private fun selectOptimalSize(targetW: Int, targetH: Int) {
        val characteristics = cameraCharacteristics ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val choices = map.getOutputSizes(ImageFormat.YUV_420_888) ?: map.getOutputSizes(SurfaceTexture::class.java)

        if (choices == null || choices.isEmpty()) return

        var bestSize: Size? = null
        val targetRatio = if (targetW > 0 && targetH > 0) {
            val max = Math.max(targetW, targetH).toDouble()
            val min = Math.min(targetW, targetH).toDouble()
            max / min
        } else {
            16.0 / 9.0
        }

        var minDiff = Double.MAX_VALUE
        for (size in choices) {
            if (size.width >= 640 && size.height >= 480) {
                val ratio = size.width.toDouble() / size.height.toDouble()
                val diff = Math.abs(ratio - targetRatio)
                if (diff < minDiff) {
                    minDiff = diff
                    bestSize = size
                } else if (Math.abs(diff - minDiff) < 0.05 && (size.width == 1920 || size.width == 1280)) {
                    bestSize = size
                }
            }
        }

        val chosen = bestSize ?: choices[0]
        previewSize = Point(chosen.width, chosen.height)
        Log.i(TAG, "Selected Camera2 preview size: ${previewSize.x} x ${previewSize.y}")
    }

    /**
     * Opens Camera2 device.
     */
    @SuppressLint("MissingPermission")
    fun openCamera() {
        startBackgroundThread()
        val id = cameraId
        if (id == null) {
            Log.e(TAG, "No suitable camera ID found")
            onCameraError?.invoke(IllegalStateException("No camera ID found"))
            return
        }

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    onCameraOpened?.invoke()
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    onCameraClosed?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    Log.e(TAG, "Camera open error: $error")
                    onCameraError?.invoke(RuntimeException("Camera open error: $error"))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            onCameraError?.invoke(e)
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return

        try {
            texture.setDefaultBufferSize(previewSize.x, previewSize.y)
            val previewSurface = Surface(texture).also { this.previewSurface = it }

            // Create ImageReader for barcode frame capture
            imageReader?.close()
            val reader = ImageReader.newInstance(
                previewSize.x,
                previewSize.y,
                ImageFormat.YUV_420_888,
                2
            )
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val requiredSize = image.width * image.height * 3 / 2
                    if (frameBuffer == null || frameBuffer!!.size != requiredSize) {
                        frameBuffer = ByteArray(requiredSize)
                    }
                    val nv21 = ImageConversionUtils.yuv420888ToNv21(image, frameBuffer)
                    onFrameAvailable?.invoke(nv21, image.width, image.height)
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting image frame", e)
                } finally {
                    image.close()
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
                        applyZoom()
                        applyTorch()
                        applyExposure()
                        startRepeatingRequest()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        onCameraError?.invoke(RuntimeException("Capture session configuration failed"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun startRepeatingRequest() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            isPreviewStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    fun startPreview() {
        if (!isPreviewStarted) {
            startRepeatingRequest()
        }
    }

    fun stopPreview() {
        try {
            captureSession?.stopRepeating()
            isPreviewStarted = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop preview", e)
        }
    }

    /**
     * Closes camera, capture session, and image reader.
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            stopPreview()
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
            onCameraClosed?.invoke()
        }
    }

    // --- Torch / Flash Control ---

    fun setTorch(enable: Boolean) {
        isTorchOn = enable
        applyTorch()
    }

    fun getTorch(): Boolean = isTorchOn

    private fun applyTorch() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            if (isTorchOn) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update torch", e)
        }
    }

    // --- Zoom Control ---

    fun setZoomIndex(index: Int) {
        val clampedIndex = index.coerceIn(0, zoomRatios.size - 1)
        currentZoomIndex = clampedIndex
        currentZoomRatio = zoomRatios[clampedIndex] / 100.0f
        applyZoom()
    }

    fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        applyZoom()
    }

    fun getZoomIndex(): Int = currentZoomIndex

    fun getZoomRatios(): List<Int> = zoomRatios

    fun getMaxZoomRatio(): Float = maxZoomRatio

    fun getCurrentZoomRatio(): Float = currentZoomRatio

    private fun applyZoom() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                cameraCharacteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
            ) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
            } else {
                val arraySize = sensorActiveArraySize ?: return
                val cropW = (arraySize.width() / currentZoomRatio).toInt()
                val cropH = (arraySize.height() / currentZoomRatio).toInt()
                val left = (arraySize.width() - cropW) / 2
                val top = (arraySize.height() - cropH) / 2
                val cropRect = Rect(left, top, left + cropW, top + cropH)
                builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
            }
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom", e)
        }
    }

    // --- Tap-to-Focus & Metering ---

    fun focusOnArea(rect: Rect?) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val activeArray = sensorActiveArraySize ?: return

        try {
            // Cancel any ongoing AF
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)

            if (rect != null) {
                val meteringRect = MeteringRectangle(
                    rect.left.coerceIn(0, activeArray.width()),
                    rect.top.coerceIn(0, activeArray.height()),
                    rect.width().coerceAtLeast(1),
                    rect.height().coerceAtLeast(1),
                    MeteringRectangle.METERING_WEIGHT_MAX
                )
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
            }

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing focus on area", e)
        }
    }

    fun cancelAutoFocus() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling autofocus", e)
        }
    }

    // --- Exposure Control ---

    fun setExposureCompensation(value: Int) {
        currentExposure = value.coerceIn(exposureRange.lower, exposureRange.upper)
        applyExposure()
    }

    fun getExposureCompensation(): Int = currentExposure

    fun getExposureRange(): Range<Int> = exposureRange

    fun getExposureStep(): Float = exposureStep

    private fun applyExposure() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposure)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply exposure compensation", e)
        }
    }

    fun getPreviewSize(): Point = Point(previewSize.x, previewSize.y)

    fun getSensorOrientation(): Int = sensorOrientation
}
