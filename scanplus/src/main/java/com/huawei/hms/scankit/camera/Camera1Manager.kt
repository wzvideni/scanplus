package com.huawei.hms.scankit.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.Camera
import android.os.Build
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import java.io.IOException

/**
 * Robust Camera1 (android.hardware.Camera) implementation for ScanKit.
 * Restores legacy device compatibility and seamlessly integrates with [ICameraManager].
 */
@Suppress("DEPRECATION")
class Camera1Manager(
    private val context: Context,
    private val lensFacing: Int = Camera.CameraInfo.CAMERA_FACING_BACK
) : ICameraManager {

    companion object {
        private const val TAG = "ScanKitCamera1"
        private const val DEFAULT_PREVIEW_WIDTH = 1280
        private const val DEFAULT_PREVIEW_HEIGHT = 720
    }

    private var camera: Camera? = null
    private var cameraId: Int = -1
    private var textureView: TextureView? = null

    private var previewSize: Point = Point(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
    private var sensorOrientation: Int = 90
    private var displayOrientation: Int = 90

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

    // Callbacks
    override var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)? = null
    override var onCameraOpened: (() -> Unit)? = null
    override var onCameraClosed: (() -> Unit)? = null
    override var onCameraError: ((Throwable) -> Unit)? = null

    init {
        val targetFacing = if (lensFacing == 1 || lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
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
            Log.e(TAG, "findCameraId error", e)
        }
        return 0
    }

    @Synchronized
    override fun openCamera() {
        if (isOpen && camera != null) {
            Log.d(TAG, "Camera1 is already open")
            return
        }

        try {
            Log.i(TAG, "Opening Camera1 with cameraId=$cameraId")
            val cam = Camera.open(cameraId) ?: throw IOException("Camera.open($cameraId) returned null")
            this.camera = cam

            initCameraParameters(cam)
            isOpen = true
            onCameraOpened?.invoke()

            // If a TextureView was attached before opening, bind it now
            val tv = textureView
            if (tv != null && tv.surfaceTexture != null) {
                cam.setPreviewTexture(tv.surfaceTexture)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open Camera1: ${e.message}", e)
            closeCamera()
            onCameraError?.invoke(e)
            throw e
        }
    }

    private fun initCameraParameters(cam: Camera) {
        val parameters = cam.parameters ?: return

        // 1. Preview size selection
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val display = windowManager?.defaultDisplay
        val displaySize = Point(1080, 1920)
        display?.getSize(displaySize)

        previewSize = selectBestPreviewSize(parameters, displaySize.y, displaySize.x)
        Log.i(TAG, "Selected Camera1 preview size: ${previewSize.x}x${previewSize.y}")
        parameters.setPreviewSize(previewSize.x, previewSize.y)
        parameters.previewFormat = ImageFormat.NV21

        // 2. Focus mode
        val supportedFocusModes = parameters.supportedFocusModes
        if (!supportedFocusModes.isNullOrEmpty()) {
            when {
                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
        }

        // 3. Zoom
        isZoomSupported = parameters.isZoomSupported
        if (isZoomSupported) {
            maxZoomIndex = parameters.maxZoom
            zoomRatios = parameters.zoomRatios ?: listOf(100)
            currentZoomIndex = parameters.zoom
        } else {
            zoomRatios = listOf(100)
            maxZoomIndex = 0
            currentZoomIndex = 0
        }

        // 4. Exposure
        val minExp = parameters.minExposureCompensation
        val maxExp = parameters.maxExposureCompensation
        exposureRange = Range(minExp, maxExp)
        exposureStep = parameters.exposureCompensationStep
        currentExposure = parameters.exposureCompensation

        // 5. Orientation
        displayOrientation = calculateOrientation()
        cam.setDisplayOrientation(displayOrientation)

        // 6. Optional vendor optimization (from original ScanKit f0.java)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val method = Camera.Parameters::class.java.getMethod("setScanOptEnable", Boolean::class.javaPrimitiveType)
                method.invoke(parameters, true)
            } catch (ignored: Exception) {}
        }

        try {
            cam.parameters = parameters
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply full Camera1 parameters", e)
        }
    }

    private fun selectBestPreviewSize(parameters: Camera.Parameters, targetWidth: Int, targetHeight: Int): Point {
        val supported = parameters.supportedPreviewSizes ?: return Point(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
        val targetRatio = targetWidth.toDouble() / targetHeight.toDouble().coerceAtLeast(1.0)

        var bestSize: Camera.Size? = null
        var minDiff = Double.MAX_VALUE

        for (size in supported) {
            if (size.width == targetWidth && size.height == targetHeight) {
                return Point(size.width, size.height)
            }
            if (size.width * size.height >= 153600) { // At least 640x480 resolution
                val ratio = size.width.toDouble() / size.height.toDouble()
                val diff = Math.abs(ratio - targetRatio)
                if (diff < minDiff) {
                    minDiff = diff
                    bestSize = size
                }
            }
        }

        return if (bestSize != null) {
            Point(bestSize.width, bestSize.height)
        } else {
            Point(supported[0].width, supported[0].height)
        }
    }

    private fun calculateOrientation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }

        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)

        return if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            val result = (info.orientation + degrees) % 360
            (360 - result) % 360
        } else {
            (info.orientation - degrees + 360) % 360
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
            } catch (e: Exception) {
                Log.e(TAG, "setPreviewTexture failed", e)
            }
        }
    }

    @Synchronized
    override fun startPreview() {
        val cam = camera ?: run {
            Log.w(TAG, "Cannot startPreview: camera is null")
            return
        }

        if (isPreviewing) {
            Log.d(TAG, "Camera1 preview is already running")
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
            Log.i(TAG, "Camera1 preview started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startPreview failed", e)
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
            Log.w(TAG, "stopPreview error: ${e.message}")
        } finally {
            isPreviewing = false
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
            Log.w(TAG, "closeCamera error: ${e.message}")
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
                val mode = if (enable) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                if (supportedFlash.contains(mode)) {
                    params.flashMode = mode
                    cam.parameters = params
                    isTorchOn = enable
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setTorch error", e)
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
            Log.w(TAG, "setZoomIndex error", e)
        }
    }

    @Synchronized
    override fun setZoomRatio(ratio: Float) {
        if (!isZoomSupported || zoomRatios.isEmpty()) return
        val targetRatioInt = (ratio * 100).toInt()
        var closestIndex = 0
        var minDiff = Int.MAX_VALUE

        for ((idx, r) in zoomRatios.withIndex()) {
            val diff = Math.abs(r - targetRatioInt)
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

    @Synchronized
    override fun focusOnArea(rect: Rect?) {
        val cam = camera ?: return
        if (rect == null) {
            cancelAutoFocus()
            return
        }

        try {
            val params = cam.parameters
            if (params.maxNumFocusAreas > 0) {
                val areaRect = mapRectToCameraCoordinates(rect)
                params.focusAreas = listOf(Camera.Area(areaRect, 1000))
                if (params.maxNumMeteringAreas > 0) {
                    params.meteringAreas = listOf(Camera.Area(areaRect, 1000))
                }
                params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                cam.parameters = params
                cam.autoFocus { _, _ ->
                    // Auto-focus completed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "focusOnArea error", e)
        }
    }

    private fun mapRectToCameraCoordinates(rect: Rect): Rect {
        val left = (rect.left * 2000 / previewSize.x.coerceAtLeast(1) - 1000).coerceIn(-1000, 1000)
        val top = (rect.top * 2000 / previewSize.y.coerceAtLeast(1) - 1000).coerceIn(-1000, 1000)
        val right = (rect.right * 2000 / previewSize.x.coerceAtLeast(1) - 1000).coerceIn(-1000, 1000)
        val bottom = (rect.bottom * 2000 / previewSize.y.coerceAtLeast(1) - 1000).coerceIn(-1000, 1000)
        return Rect(
            Math.min(left, right),
            Math.min(top, bottom),
            Math.max(left, right),
            Math.max(top, bottom)
        )
    }

    @Synchronized
    override fun cancelAutoFocus() {
        try {
            camera?.cancelAutoFocus()
        } catch (e: Exception) {
            Log.w(TAG, "cancelAutoFocus error: ${e.message}")
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
            Log.w(TAG, "setExposureCompensation error", e)
        }
    }

    override fun getExposureCompensation(): Int = currentExposure

    override fun getExposureRange(): Range<Int> = exposureRange

    override fun getExposureStep(): Float = exposureStep

    override fun getPreviewSize(): Point = Point(previewSize.x, previewSize.y)

    override fun getSensorOrientation(): Int = sensorOrientation

    @Synchronized
    fun setDisplayOrientation(degrees: Int) {
        displayOrientation = degrees
        try {
            camera?.setDisplayOrientation(degrees)
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayOrientation error", e)
        }
    }
}
