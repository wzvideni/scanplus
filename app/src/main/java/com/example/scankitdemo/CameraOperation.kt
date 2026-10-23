package com.example.scankitdemo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.huawei.hms.scankit.util.ImageConversionUtils
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Modern Camera2 implementation of [CameraOperation] in Kotlin.
 * Replaces the legacy Camera1 API for MultiProcessor scanning in [CommonActivity].
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

    private val targetHandlerRef = AtomicReference<Handler?>()
    private val cameraOpenCloseLock = Semaphore(1)

    private var maxZoomRatio: Float = 1.0f
    private var sensorActiveArraySize: Rect? = null

    /**
     * Opens the camera using Camera2 and attaches to the SurfaceHolder.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun open(holder: SurfaceHolder?) {
        if (holder == null) return
        startBackgroundThread()

        val cm = (cameraManager ?: run {
            // We can retrieve CameraManager via reflection or common service
            val appClass = Class.forName("android.app.ActivityThread")
            val appMethod = appClass.getMethod("currentApplication")
            val app = appMethod.invoke(null) as Context
            (app.getSystemService(Context.CAMERA_SERVICE) as CameraManager).also { cameraManager = it }
        })

        try {
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

            val id = cameraId ?: return

            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    createSession(holder)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            maxZoomRatio = range?.upper ?: chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        } else {
            maxZoomRatio = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        }
    }

    private fun createSession(holder: SurfaceHolder) {
        val device = cameraDevice ?: return
        try {
            val surface = holder.surface
            previewSurface = surface

            imageReader?.close()
            val reader = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                val handler = targetHandlerRef.getAndSet(null)
                try {
                    if (handler != null) {
                        val nv21 = ImageConversionUtils.yuv420888ToNv21(image)
                        val message = Message.obtain(handler, 0, image.width, image.height, nv21)
                        message.sendToTarget()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in image callback", e)
                } finally {
                    image.close()
                }
            }, backgroundHandler)

            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        startRepeating()
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
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
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
        if (cameraDevice != null && isPreview) {
            targetHandlerRef.set(handler)
            if (zoomValue != defaultZoom) {
                setZoom(zoomValue.toFloat())
            }
        }
    }

    private fun setZoom(zoomValue: Float) {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
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
