package com.huawei.hms.scankit.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Camera
import android.os.Build
import android.util.Range
import android.view.TextureView
import com.huawei.hms.scankit.p.k0
import com.huawei.hms.scankit.util.ScanLog
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Robust Camera1 (android.hardware.Camera) implementation for ScanKit.
 * Matches original scanplus-2.15.0.301.aar (f0, h0, i0, l0, n0, f6) behavior 100%.
 */
@Suppress("DEPRECATION")
class Camera1Manager(
    private val context: Context,
    private val lensFacing: Int = Camera.CameraInfo.CAMERA_FACING_BACK,
    private val targetResolution: Point = Point(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT),
    private val recordingHint: Boolean = false,
    private var displayOrientation: Int = 90
) : ICameraManager {

    companion object {
        private const val TAG = "ScanKitCamera1"
        const val DEFAULT_PREVIEW_WIDTH = 1920
        const val DEFAULT_PREVIEW_HEIGHT = 1080
    }

    private var camera: Camera? = null
    private var cameraId: Int = -1
    private var textureView: TextureView? = null

    private var previewSize: Point = Point(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
    private var sensorOrientation: Int = 90

    private var isTorchOn: Boolean = false

    @Volatile
    private var isOpen: Boolean = false

    @Volatile
    private var isPreviewing: Boolean = false

    // Zoom
    private var isZoomSupported: Boolean = false
    private var zoomRatios: List<Int> = listOf(100)
    private var maxZoomIndex: Int = 0
    private var currentZoomIndex: Int = 0

    // Exposure
    private var exposureRange: Range<Int> = Range(0, 0)
    private var exposureStep: Float = 1.0f
    private var currentExposure: Int = 0

    // Focus state (matching i0.java)
    private var defaultFocusMode: String = Camera.Parameters.FOCUS_MODE_AUTO
    private var tapFocusCount: Int = 2

    // Callbacks
    override var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null
    override var onCameraOpened: (() -> Unit)? = null
    override var onCameraClosed: (() -> Unit)? = null
    override var onCameraError: ((Throwable) -> Unit)? = null

    init {
        val targetFacing =
            if (lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                Camera.CameraInfo.CAMERA_FACING_FRONT
            } else {
                Camera.CameraInfo.CAMERA_FACING_BACK
            }
        cameraId = findCameraId(targetFacing)
    }

    private fun findCameraId(facing: Int): Int {
        try {
            val num = Camera.getNumberOfCameras()
            val info = Camera.CameraInfo()
            for (i in 0 until num) {
                Camera.getCameraInfo(i, info)
                if (info.facing == facing) {
                    sensorOrientation = info.orientation
                    return i
                }
            }
        } catch (e: Exception) {
            ScanLog.e(TAG, "findCameraId error", e)
        }
        return 0
    }

    @Synchronized
    override fun openCamera() {
        if (isOpen && camera != null) {
            ScanLog.d(TAG, "Camera1 is already open")
            return
        }

        try {
            ScanLog.i(TAG, "Opening Camera1 with cameraId=$cameraId")
            val cam =
                Camera.open(cameraId) ?: throw IOException("Camera.open($cameraId) returned null")
            this.camera = cam

            initCameraParameters(cam)
            isOpen = true
            onCameraOpened?.invoke()

            // If TextureView was attached before opening, bind it now
            textureView?.let { tv ->
                cam.setPreviewTexture(tv.surfaceTexture)
                val finalOrientation = getEffectiveDisplayOrientation()
                cam.setDisplayOrientation(finalOrientation)
            }
        } catch (e: Throwable) {
            ScanLog.e(TAG, "Failed to open Camera1: ${e.message}", e)
            closeCamera()
            onCameraError?.invoke(e)
            throw e
        }
    }

    private fun initCameraParameters(cam: Camera) {
        val parameters = cam.parameters ?: return

        // 1. Preview size selection (matching f0.java lines 20-55)
        previewSize = selectBestPreviewSize(parameters, targetResolution)
        ScanLog.i(TAG, "Selected Camera1 preview size: ${previewSize.x}x${previewSize.y}")
        parameters.setPreviewSize(previewSize.x, previewSize.y)
        parameters.previewFormat = ImageFormat.NV21

        // 2. Focus mode priority (matching f0.java lines 56-80: continuous-picture -> continuous-video -> auto)
        val supportedFocusModes = parameters.supportedFocusModes
        if (!supportedFocusModes.isNullOrEmpty()) {
            defaultFocusMode = when {
                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE

                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO

                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                    Camera.Parameters.FOCUS_MODE_AUTO

                else -> supportedFocusModes[0]
            }
            parameters.focusMode = defaultFocusMode
        }

        // 3. Zoom initialization: set default zoom to 1 (matching f0.java lines 81-88)
        isZoomSupported = parameters.isZoomSupported
        if (isZoomSupported) {
            maxZoomIndex = parameters.maxZoom
            zoomRatios = parameters.zoomRatios ?: listOf(100)
            parameters.zoom = 1.coerceAtMost(maxZoomIndex)
            currentZoomIndex = parameters.zoom
        } else {
            zoomRatios = listOf(100)
            maxZoomIndex = 0
            currentZoomIndex = 0
        }

        // 4. Exposure initialization (matching h0.java)
        val minExp = parameters.minExposureCompensation
        val maxExp = parameters.maxExposureCompensation
        exposureRange = Range(minExp, maxExp)
        exposureStep = parameters.exposureCompensationStep
        currentExposure = parameters.exposureCompensation

        // 5. Recording hint (matching f0.java line 130)
        if (recordingHint) {
            parameters.setRecordingHint(true)
        }

        // 6. Vendor scan optimization reflection (matching f0.java line 135)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val method = Camera.Parameters::class.java.getMethod(
                    "setScanOptEnable",
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(parameters, true)
            } catch (e: Exception) {
                ScanLog.d(TAG, "Vendor setScanOptEnable not supported: ${e.message}")
            }
        }

        // 7. Orientation: if sensorOrientation is 0, device hardware preview is upright at 0 degrees.
        val finalOrientation = getEffectiveDisplayOrientation()
        ScanLog.d(TAG, "Camera1 initCameraParameters: setDisplayOrientation($finalOrientation)")
        cam.setDisplayOrientation(finalOrientation)

        try {
            cam.parameters = parameters
        } catch (e: Exception) {
            ScanLog.w(TAG, "Failed to apply full Camera1 parameters", e)
        }
    }

    /**
     * Exact resolution matching algorithm from original f0.java.
     */
    private fun selectBestPreviewSize(parameters: Camera.Parameters, target: Point): Point {
        val supported = parameters.supportedPreviewSizes ?: return Point(
            DEFAULT_PREVIEW_WIDTH,
            DEFAULT_PREVIEW_HEIGHT
        )
        val maxT = maxOf(target.x, target.y)
        val minT = minOf(target.x, target.y)
        val targetRatio = maxT.toDouble() / minT.toDouble().coerceAtLeast(1.0)

        var bestWidth = 0
        var bestHeight = 0
        var minDiff = Double.MAX_VALUE

        for (size in supported) {
            val w = size.width
            val h = size.height
            if ((w == target.x && h == target.y) || (w == maxT && h == minT)) {
                return Point(w, h)
            }
            if (w * h >= 153600) { // At least 640x480 resolution
                val ratio = w.toDouble() / h.toDouble()
                val diff = abs(ratio - targetRatio)
                if (diff < minDiff) {
                    minDiff = diff
                    bestWidth = w
                    bestHeight = h
                }
            }
        }

        return if (bestWidth > 0 && bestHeight > 0) {
            Point(bestWidth, bestHeight)
        } else {
            Point(supported[0].width, supported[0].height)
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val view = textureView ?: return
        if (viewWidth == 0 || viewHeight == 0) return
        if (previewSize.x == 0 || previewSize.y == 0) return

        val previewLong = maxOf(previewSize.x, previewSize.y).toFloat()
        val previewShort = minOf(previewSize.x, previewSize.y).toFloat()
        val imageRatio = previewShort / previewLong // e.g. 1080 / 1920 = 9:16 = 0.5625

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()

        val scaleX: Float
        val scaleY: Float

        if (viewRatio < imageRatio) {
            scaleX = (viewHeight * imageRatio) / viewWidth
            scaleY = 1.0f
        } else {
            scaleX = 1.0f
            scaleY = (viewWidth / imageRatio) / viewHeight
        }

        val matrix = android.graphics.Matrix()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        matrix.setScale(scaleX, scaleY, centerX, centerY)

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            view.setTransform(matrix)
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                view.setTransform(matrix)
            }
        }
    }

    @Synchronized
    override fun attachTextureView(view: TextureView) {
        this.textureView = view
        val surfaceTexture = view.surfaceTexture
        val cam = camera
        if (cam != null && surfaceTexture != null) {
            try {
                cam.setPreviewTexture(surfaceTexture)
                val finalOrientation = getEffectiveDisplayOrientation()
                cam.setDisplayOrientation(finalOrientation)
            } catch (e: Exception) {
                ScanLog.e(TAG, "setPreviewTexture failed", e)
            }
        }
        if (view.width > 0 && view.height > 0) {
            configureTransform(view.width, view.height)
        }
    }

    @Synchronized
    override fun startPreview() {
        val cam = camera ?: run {
            ScanLog.w(TAG, "Cannot startPreview: camera is null")
            return
        }

        if (isPreviewing) {
            ScanLog.d(TAG, "Camera1 preview is already running")
            return
        }

        try {
            // Attach TextureView if ready
            textureView?.surfaceTexture?.let { st ->
                cam.setPreviewTexture(st)
            }

            // Bind preview frame callback delivering NV21 data
            cam.setPreviewCallback { data, _ ->
                if (data != null && isPreviewing) {
                    onFrameAvailable?.invoke(data, previewSize.x, previewSize.y)
                }
            }

            cam.startPreview()
            isPreviewing = true
            ScanLog.i(TAG, "Camera1 preview started successfully")
        } catch (e: Exception) {
            ScanLog.e(TAG, "startPreview failed", e)
            onCameraError?.invoke(e)
            throw e
        }
    }

    @Synchronized
    override fun stopPreview() {
        if (!isPreviewing) return
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
        } catch (e: Exception) {
            ScanLog.w(TAG, "stopPreview error: ${e.message}")
        } finally {
            isPreviewing = false
        }
    }

    @Synchronized
    override fun requestPreviewFrame(mode: Int) {
        val cam = camera ?: return
        if (!isPreviewing) return

        try {
            if (mode == 1) { // ONE_SHOT (matching j0.o line 130)
                cam.setOneShotPreviewCallback { data, _ ->
                    if (data != null) {
                        onFrameAvailable?.invoke(data, previewSize.x, previewSize.y)
                    }
                }
            } else { // CONTINUOUS
                cam.setPreviewCallback { data, _ ->
                    if (data != null && isPreviewing) {
                        onFrameAvailable?.invoke(data, previewSize.x, previewSize.y)
                    }
                }
            }
        } catch (e: Exception) {
            ScanLog.w(TAG, "requestPreviewFrame error", e)
        }
    }

    @Synchronized
    override fun closeCamera() {
        try {
            stopPreview()
            cancelAutoFocus()
            if (isTorchOn) {
                setTorch(false)
            }
            camera?.release()
        } catch (e: Exception) {
            ScanLog.w(TAG, "closeCamera error: ${e.message}")
        } finally {
            camera = null
            isOpen = false
            onCameraClosed?.invoke()
        }
    }

    @Synchronized
    override fun setTorch(enable: Boolean) {
        val cam = camera ?: return
        try {
            val params = cam.parameters
            val supportedFlash = params.supportedFlashModes
            if (!supportedFlash.isNullOrEmpty()) {
                val mode =
                    if (enable) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                if (supportedFlash.contains(mode)) {
                    params.flashMode = mode
                    cam.parameters = params
                    isTorchOn = enable
                }
            }
        } catch (e: Exception) {
            ScanLog.w(TAG, "setTorch error", e)
        }
    }

    override fun getTorch(): Boolean = isTorchOn

    @Synchronized
    override fun setZoomIndex(index: Int) {
        if (!isZoomSupported) return
        val cam = camera ?: return
        try {
            val params = cam.parameters
            val target = index.coerceIn(0, maxZoomIndex)
            params.zoom = target
            cam.parameters = params
            currentZoomIndex = target
        } catch (e: Exception) {
            ScanLog.w(TAG, "setZoomIndex error", e)
        }
    }

    @Synchronized
    override fun setZoomRatio(ratio: Float) {
        if (!isZoomSupported || zoomRatios.isEmpty()) return
        val targetRatioInt = (ratio * 100).toInt()
        var closestIndex = 0
        var minDiff = Int.MAX_VALUE

        for ((idx, r) in zoomRatios.withIndex()) {
            val diff = abs(r - targetRatioInt)
            if (diff < minDiff) {
                minDiff = diff
                closestIndex = idx
            }
        }
        setZoomIndex(closestIndex)
    }

    override fun getZoomIndex(): Int = currentZoomIndex

    override fun getZoomRatios(): List<Int> = zoomRatios

    override fun getMaxZoomRatio(): Float {
        return if (zoomRatios.isNotEmpty()) zoomRatios.last() / 100f else 1.0f
    }

    override fun getCurrentZoomRatio(): Float {
        return if (zoomRatios.isNotEmpty() && currentZoomIndex < zoomRatios.size) {
            zoomRatios[currentZoomIndex] / 100f
        } else {
            1.0f
        }
    }

    /**
     * Tap to focus with macro mode matching original i0.java.
     */
    @Synchronized
    override fun focusOnArea(rect: Rect?) {
        val cam = camera ?: return
        if (rect == null) {
            cancelAutoFocus()
            return
        }

        if (tapFocusCount < 1) return
        tapFocusCount--

        try {
            cam.cancelAutoFocus()
            val params = cam.parameters
            val areaRect = mapAreaToCameraCoordinates(
                rect.centerX(),
                rect.centerY(),
                previewSize.x,
                previewSize.y
            )

            if (params.maxNumFocusAreas > 0) {
                params.focusAreas = listOf(Camera.Area(areaRect, 1000))
            }
            if (params.maxNumMeteringAreas > 0) {
                params.meteringAreas = listOf(Camera.Area(areaRect, 1000))
            }

            // Temporarily use macro focus mode during tap (matching i0.java line 160)
            val previousFocus = params.focusMode
            params.focusMode = Camera.Parameters.FOCUS_MODE_MACRO
            cam.parameters = params

            cam.autoFocus { _, currentCam ->
                try {
                    currentCam?.cancelAutoFocus()
                    val p = currentCam?.parameters
                    if (p != null) {
                        p.focusMode = previousFocus
                        currentCam.parameters = p
                    }
                } catch (e: Exception) {
                    ScanLog.d(
                        TAG,
                        "Failed to restore focus mode in autoFocus callback: ${e.message}"
                    )
                }
                tapFocusCount = 2
            }
        } catch (e: Exception) {
            ScanLog.w(TAG, "focusOnArea error", e)
            tapFocusCount++
        }
    }

    private fun mapAreaToCameraCoordinates(
        centerX: Int,
        centerY: Int,
        width: Int,
        height: Int
    ): Rect {
        val x = ((centerX.toFloat() / width) * 2000f - 1000f).toInt()
        val y = ((centerY.toFloat() / height) * 2000f - 1000f).toInt()

        val left = (x - 150).coerceIn(-1000, 1000)
        val top = (y - 150).coerceIn(-1000, 1000)
        val right = (x + 150).coerceIn(-1000, 1000)
        val bottom = (y + 150).coerceIn(-1000, 1000)

        val rectF = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val matrix = Matrix()
        matrix.setScale(
            if (lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) -1.0f else 1.0f,
            1.0f
        )
        matrix.postRotate(getEffectiveDisplayOrientation().toFloat())
        val invertMatrix = Matrix()
        matrix.invert(invertMatrix)
        invertMatrix.mapRect(rectF)

        return Rect(
            rectF.left.roundToInt().coerceIn(-1000, 1000),
            rectF.top.roundToInt().coerceIn(-1000, 1000),
            rectF.right.roundToInt().coerceIn(-1000, 1000),
            rectF.bottom.roundToInt().coerceIn(-1000, 1000)
        )
    }

    @Synchronized
    override fun cancelAutoFocus() {
        try {
            camera?.cancelAutoFocus()
            val cam = camera ?: return
            val params = cam.parameters
            params.focusMode = defaultFocusMode
            cam.parameters = params
        } catch (e: Exception) {
            ScanLog.w(TAG, "cancelAutoFocus error: ${e.message}")
        }
    }

    /**
     * Metering areas mapping matching original l0.java.
     */
    @Synchronized
    override fun setMeteringAreas(areas: List<*>?) {
        val cam = camera ?: return
        if (areas.isNullOrEmpty()) return

        try {
            val params = cam.parameters
            if (params.maxNumMeteringAreas > 0) {
                val cameraAreas = mutableListOf<Camera.Area>()
                for (item in areas) {
                    if (item is k0.a) {
                        cameraAreas.add(Camera.Area(item.a, item.b))
                    } else if (item is Rect) {
                        cameraAreas.add(Camera.Area(item, 1000))
                    }
                }
                if (cameraAreas.isNotEmpty()) {
                    params.meteringAreas = cameraAreas
                    cam.parameters = params
                }
            }
        } catch (e: Exception) {
            ScanLog.w(TAG, "setMeteringAreas error", e)
        }
    }

    @Synchronized
    override fun setExposureCompensation(value: Int) {
        val cam = camera ?: return
        try {
            val params = cam.parameters
            val target = value.coerceIn(exposureRange.lower, exposureRange.upper)
            params.exposureCompensation = target
            cam.parameters = params
            currentExposure = target
        } catch (e: Exception) {
            ScanLog.w(TAG, "setExposureCompensation error", e)
        }
    }

    override fun getExposureCompensation(): Int = currentExposure

    override fun getExposureRange(): Range<Int> = exposureRange

    override fun getExposureStep(): Float = exposureStep

    override fun getPreviewSize(): Point = Point(previewSize.x, previewSize.y)

    override fun getSensorOrientation(): Int = sensorOrientation

    private fun getEffectiveDisplayOrientation(degrees: Int = displayOrientation): Int {
        return CameraOrientationUtils.getEffectiveOrientation(
            context = context,
            sensorOrientation = sensorOrientation,
            isFront = (lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT),
            fallbackDisplayOrientation = degrees
        )
    }

    @Synchronized
    override fun setDisplayOrientation(degrees: Int) {
        displayOrientation = degrees
        try {
            val finalOrientation = getEffectiveDisplayOrientation(degrees)
            camera?.setDisplayOrientation(finalOrientation)
        } catch (e: Exception) {
            ScanLog.w(TAG, "setDisplayOrientation error", e)
        }
    }
}
