package com.huawei.hms.scankit.camera

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.huawei.hms.scankit.p.k0
import com.huawei.hms.scankit.util.ImageConversionUtils
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Modern Camera2 implementation replacing legacy Camera1 API.
 * Encapsulates camera lifecycle, preview rendering, frame streaming,
 * tap-to-focus, zoom, and torch control.
 *
 * Designed to handle asynchronous lifecycle events gracefully, regardless
 * of the order in which [openCamera], [attachTextureView], and [startPreview] are invoked.
 */
class Camera2Manager(
    private val context: Context,
    private val lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK
) : ICameraManager {
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
    private var currentSurfaceTexture: SurfaceTexture? = null
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
    @Volatile
    private var isPreviewStarted: Boolean = false
    @Volatile
    private var shouldStartPreview: Boolean = false
    @Volatile
    private var isSessionCreating: Boolean = false
    @Volatile
    private var isCameraOpening: Boolean = false
    private var openRetryCount = 0

    private val cameraOpenCloseLock = Semaphore(1)

    // Preallocated Frame Buffer
    private var frameBuffer: ByteArray? = null

    // Listeners
    override var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null
    override var onCameraOpened: (() -> Unit)? = null
    override var onCameraClosed: (() -> Unit)? = null
    override var onCameraError: ((Throwable) -> Unit)? = null

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
            backgroundThread?.join(300)
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
    override fun attachTextureView(view: TextureView) {
        this.textureView = view
        selectOptimalSize(view.width, view.height)

        if (view.isAvailable && view.surfaceTexture != null) {
            configureTransform(view.width, view.height)
            checkAndCreateSession()
        }

        // Listen for layout size changes without replacing existing surfaceTextureListeners
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                configureTransform(w, h)
                if (view.isAvailable && view.surfaceTexture != null) {
                    checkAndCreateSession()
                }
            }
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val view = textureView ?: return
        if (viewWidth == 0 || viewHeight == 0) return

        val activity = context as? Activity
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.y.toFloat(), previewSize.x.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(
                viewHeight.toFloat() / previewSize.y,
                viewWidth.toFloat() / previewSize.x
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.setTransform(matrix)
        } else {
            Handler(Looper.getMainLooper()).post {
                view.setTransform(matrix)
            }
        }
    }

    private fun selectOptimalSize(targetW: Int, targetH: Int) {
        val characteristics = cameraCharacteristics ?: return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        val surfaceSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
        val commonSizes = yuvSizes.filter { surfaceSizes.contains(it) }
        val choices = if (commonSizes.isNotEmpty()) commonSizes else if (surfaceSizes.isNotEmpty()) surfaceSizes else yuvSizes

        if (choices.isEmpty()) return

        val size1080p = choices.firstOrNull { it.width == 1920 && it.height == 1080 }
        val size720p = choices.firstOrNull { it.width == 1280 && it.height == 720 }

        // Match original ScanKit AAR preference: 1080p for highest recognition accuracy
        if (size1080p != null) {
            previewSize = Point(1920, 1080)
            Log.i(TAG, "Selected preferred 1080p preview size: 1920 x 1080")
            return
        }

        if (size720p != null) {
            previewSize = Point(1280, 720)
            Log.i(TAG, "Selected fallback 720p preview size: 1280 x 720")
            return
        }

        // Otherwise find closest 16:9 ratio
        var bestSize: Size = choices[0]
        var minDiff = Double.MAX_VALUE
        val targetRatio = 16.0 / 9.0

        for (size in choices) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            val diff = Math.abs(ratio - targetRatio)
            if (size.width in 640..1920 && diff < minDiff) {
                minDiff = diff
                bestSize = size
            }
        }

        previewSize = Point(bestSize.width, bestSize.height)
        Log.i(TAG, "Selected optimal Camera2 preview size: ${previewSize.x} x ${previewSize.y}")
    }

    /**
     * Opens Camera2 device. Handles retries automatically if camera service is busy.
     */
    @SuppressLint("MissingPermission")
    override fun openCamera() {
        startBackgroundThread()
        if (cameraDevice != null) {
            Log.d(TAG, "openCamera: cameraDevice already open")
            checkAndCreateSession()
            return
        }
        if (isCameraOpening) {
            Log.d(TAG, "openCamera: camera opening already in progress")
            return
        }
        val id = cameraId ?: run {
            Log.e(TAG, "No suitable camera ID found")
            onCameraError?.invoke(IllegalStateException("No camera ID found"))
            return
        }

        isCameraOpening = true
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                isCameraOpening = false
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    isCameraOpening = false
                    openRetryCount = 0
                    Log.i(TAG, "CameraDevice opened successfully: id=$id")
                    onCameraOpened?.invoke()
                    checkAndCreateSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpening = false
                    Log.w(TAG, "CameraDevice disconnected")
                    onCameraClosed?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    isCameraOpening = false
                    Log.e(TAG, "Camera open error: $error (retry=$openRetryCount)")

                    if ((error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ||
                         error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE) &&
                        openRetryCount < 3
                    ) {
                        openRetryCount++
                        Log.w(TAG, "Camera busy or device error, retrying open in 250ms (attempt $openRetryCount/3)...")
                        backgroundHandler?.postDelayed({
                            openCamera()
                        }, 250)
                        return
                    }

                    openRetryCount = 0
                    onCameraError?.invoke(RuntimeException("Camera open error: $error"))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            isCameraOpening = false
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
            onCameraError?.invoke(e)
        }
    }

    /**
     * Safely triggers capture session creation if both device and surface are available.
     */
    fun checkAndCreateSession() {
        if (cameraDevice == null || textureView?.surfaceTexture == null) {
            Log.d(TAG, "checkAndCreateSession: Waiting for both cameraDevice (${cameraDevice != null}) and surfaceTexture (${textureView?.surfaceTexture != null})")
            return
        }
        val handler = backgroundHandler
        if (handler != null) {
            handler.post {
                createCaptureSession()
            }
        } else {
            createCaptureSession()
        }
    }

    @Synchronized
    private fun getOrCreatePreviewSurface(texture: SurfaceTexture): Surface {
        val existing = previewSurface
        if (existing != null && existing.isValid && currentSurfaceTexture === texture) {
            return existing
        }
        try {
            existing?.release()
        } catch (e: Exception) {
            // Ignore
        }
        currentSurfaceTexture = texture
        val newSurface = Surface(texture)
        previewSurface = newSurface
        return newSurface
    }

    @Synchronized
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return

        if (captureSession != null) {
            Log.d(TAG, "createCaptureSession: session already exists, starting repeating if requested")
            if (shouldStartPreview) {
                startRepeatingRequest()
            }
            return
        }

        if (isSessionCreating) {
            Log.d(TAG, "createCaptureSession: already creating session, skipping duplicate call")
            return
        }
        isSessionCreating = true

        try {
            texture.setDefaultBufferSize(previewSize.x, previewSize.y)
            val pSurface = getOrCreatePreviewSurface(texture)

            // Create ImageReader for barcode frame capture
            imageReader?.close()
            val reader = ImageReader.newInstance(
                previewSize.x,
                previewSize.y,
                ImageFormat.YUV_420_888,
                3
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
                addTarget(pSurface)
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                // AF mode
                val afModes = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val afMode = when {
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                    else -> CaptureRequest.CONTROL_AF_MODE_OFF
                }
                set(CaptureRequest.CONTROL_AF_MODE, afMode)

                // AE mode
                val aeModes = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
                val aeMode = when {
                    aeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON) -> CaptureRequest.CONTROL_AE_MODE_ON
                    else -> CaptureRequest.CONTROL_AE_MODE_OFF
                }
                set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            }
            previewRequestBuilder = builder

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(pSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        isSessionCreating = false
                        if (cameraDevice == null) {
                            session.close()
                            return
                        }
                        captureSession = session
                        applyZoom()
                        applyTorch()
                        applyExposure()
                        Log.i(TAG, "CameraCaptureSession configured successfully, shouldStartPreview=$shouldStartPreview")
                        if (shouldStartPreview) {
                            startRepeatingRequest()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        isSessionCreating = false
                        Log.e(TAG, "Capture session configuration failed with size ${previewSize.x}x${previewSize.y}")

                        // If 1080p failed, automatically fallback to 720p
                        if (previewSize.x == 1920 && previewSize.y == 1080) {
                            Log.w(TAG, "Retrying capture session with 720p fallback...")
                            previewSize = Point(1280, 720)
                            backgroundHandler?.post {
                                createCaptureSession()
                            }
                            return
                        }
                        onCameraError?.invoke(RuntimeException("Capture session configuration failed"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            isSessionCreating = false
            Log.e(TAG, "Failed to create capture session", e)
            onCameraError?.invoke(e)
        }
    }

    private fun startRepeatingRequest() {
        val session = captureSession
        val builder = previewRequestBuilder
        if (session == null || builder == null) {
            Log.d(TAG, "startRepeatingRequest: session or builder not ready yet (session=${session != null}, builder=${builder != null})")
            return
        }
        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            isPreviewStarted = true
            Log.i(TAG, "Camera2 preview repeating request started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    override fun startPreview() {
        Log.i(TAG, "startPreview called, captureSession=${captureSession != null}")
        shouldStartPreview = true
        backgroundHandler?.post {
            if (captureSession != null && !isPreviewStarted) {
                startRepeatingRequest()
            } else if (captureSession == null) {
                checkAndCreateSession()
            }
        }
    }

    override fun stopPreview() {
        shouldStartPreview = false
        backgroundHandler?.post {
            try {
                captureSession?.stopRepeating()
                isPreviewStarted = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop preview", e)
            }
        }
    }

    override fun requestPreviewFrame(mode: Int) {
        // In Camera2, frames stream continuously via ImageReader; no-op needed
    }

    /**
     * Closes camera, capture session, and image reader synchronously.
     */
    override fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            shouldStartPreview = false
            isPreviewStarted = false
            isSessionCreating = false
            isCameraOpening = false
            openRetryCount = 0

            try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
            } catch (e: Exception) {
                // Ignore
            }
            try {
                captureSession?.close()
            } catch (e: Exception) {
                // Ignore
            }
            captureSession = null

            try {
                cameraDevice?.close()
            } catch (e: Exception) {
                // Ignore
            }
            cameraDevice = null

            try {
                imageReader?.close()
            } catch (e: Exception) {
                // Ignore
            }
            imageReader = null

            try {
                previewSurface?.release()
            } catch (e: Exception) {
                // Ignore
            }
            previewSurface = null
            currentSurfaceTexture = null
            previewRequestBuilder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        } finally {
            cameraOpenCloseLock.release()
            stopBackgroundThread()
            onCameraClosed?.invoke()
        }
    }

    // --- Torch / Flash Control ---

    override fun setTorch(enable: Boolean) {
        isTorchOn = enable
        applyTorch()
    }

    override fun getTorch(): Boolean = isTorchOn

    private fun applyTorch() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            val hasFlash = cameraCharacteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) {
                Log.w(TAG, "Flash not available on current camera")
                return
            }
            if (isTorchOn) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            if (isPreviewStarted) {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update torch", e)
        }
    }

    // --- Zoom Control ---

    override fun setZoomIndex(index: Int) {
        val clampedIndex = index.coerceIn(0, zoomRatios.size - 1)
        currentZoomIndex = clampedIndex
        currentZoomRatio = zoomRatios[clampedIndex] / 100.0f
        applyZoom()
    }

    override fun setZoomRatio(ratio: Float) {
        currentZoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        applyZoom()
    }

    override fun getZoomIndex(): Int = currentZoomIndex

    override fun getZoomRatios(): List<Int> = zoomRatios

    override fun getMaxZoomRatio(): Float = maxZoomRatio

    override fun getCurrentZoomRatio(): Float = currentZoomRatio

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
            if (isPreviewStarted) {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply zoom", e)
        }
    }

    // --- Tap-to-Focus & Metering ---

    override fun focusOnArea(rect: Rect?) {
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

    override fun cancelAutoFocus() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            if (isPreviewStarted) {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling autofocus", e)
        }
    }

    override fun setMeteringAreas(areas: List<*>?) {
        if (areas.isNullOrEmpty()) return
        val first = areas[0]
        if (first is Rect) {
            focusOnArea(first)
        } else if (first is k0.a) {
            focusOnArea(first.a)
        }
    }

    // --- Exposure Control ---

    override fun setExposureCompensation(value: Int) {
        currentExposure = value.coerceIn(exposureRange.lower, exposureRange.upper)
        applyExposure()
    }

    override fun getExposureCompensation(): Int = currentExposure

    override fun getExposureRange(): Range<Int> = exposureRange

    override fun getExposureStep(): Float = exposureStep

    private fun applyExposure() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentExposure)
            if (isPreviewStarted) {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply exposure compensation", e)
        }
    }

    override fun getPreviewSize(): Point = Point(previewSize.x, previewSize.y)

    override fun getSensorOrientation(): Int = sensorOrientation
}
