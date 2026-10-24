package com.huawei.hms.scankit.camera2

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import com.huawei.hms.feature.dynamic.ObjectWrapper
import com.huawei.hms.hmsscankit.DetailRect
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScanBase
import com.huawei.hms.scankit.ScanPlusConfig
import com.huawei.hms.scankit.camera.Camera1Manager
import com.huawei.hms.scankit.camera.Camera2Manager
import com.huawei.hms.scankit.camera.CameraMode
import com.huawei.hms.scankit.camera.ICameraManager
import com.huawei.hms.scankit.p.h4
import com.huawei.hms.scankit.p.s6
import com.huawei.hms.scankit.p.y6
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern, coroutine-based barcode scanner manager in Kotlin.
 * Supports [CameraMode.AUTO], [CameraMode.CAMERA1], and [CameraMode.CAMERA2].
 *
 * Connects directly to Android's camera pipeline and Huawei ScanKit's native
 * recognition engine with automatic far-distance zoom and backpressure handling.
 */
class Camera2ScanManager(
    private val context: Context,
    private val scanFormat: Int = HmsScanBase.ALL_SCAN_TYPE,
    private val autoZoomEnabled: Boolean = true,
    var cameraMode: CameraMode = ScanPlusConfig.cameraMode
) {
    companion object {
        private const val TAG = "Camera2ScanManager"
    }

    private var activeCamera: ICameraManager? = null
    private val camera1Manager by lazy { Camera1Manager(context) }
    private val camera2Manager by lazy { Camera2Manager(context) }

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isDecoding = AtomicBoolean(false)
    private var lastZoomTime = 0L
    private var textureView: TextureView? = null

    var onScanResult: ((Array<HmsScan>) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onTorchStatusChange: ((Boolean) -> Unit)? = null

    private fun setupCameraCallbacks(camera: ICameraManager) {
        camera.onFrameAvailable = { nv21Bytes, width, height ->
            processFrame(nv21Bytes, width, height)
        }
        camera.onCameraError = { error ->
            onError?.invoke(error)
        }
    }

    /**
     * Attaches the preview TextureView.
     */
    fun attachPreview(textureView: TextureView) {
        this.textureView = textureView
        activeCamera?.attachTextureView(textureView)
    }

    /**
     * Starts camera and barcode scanning with auto-fallback support.
     */
    fun start() {
        if (activeCamera == null) {
            when (cameraMode) {
                CameraMode.CAMERA1 -> {
                    setupCameraCallbacks(camera1Manager)
                    camera1Manager.openCamera()
                    activeCamera = camera1Manager
                }
                CameraMode.CAMERA2 -> {
                    setupCameraCallbacks(camera2Manager)
                    camera2Manager.openCamera()
                    activeCamera = camera2Manager
                }
                CameraMode.AUTO -> {
                    var c1Success = false
                    try {
                        Log.i(TAG, "AUTO mode: Starting with Camera1...")
                        setupCameraCallbacks(camera1Manager)
                        camera1Manager.openCamera()
                        activeCamera = camera1Manager
                        c1Success = true
                    } catch (e: Throwable) {
                        Log.w(TAG, "Camera1 open failed in AUTO mode: ${e.message}, falling back to Camera2...")
                    }

                    if (!c1Success) {
                        setupCameraCallbacks(camera2Manager)
                        camera2Manager.openCamera()
                        activeCamera = camera2Manager
                    }
                }
            }
        }

        textureView?.let { activeCamera?.attachTextureView(it) }

        try {
            activeCamera?.startPreview()
        } catch (e: Exception) {
            if (cameraMode == CameraMode.AUTO && activeCamera === camera1Manager) {
                Log.w(TAG, "Camera1 startPreview failed in AUTO mode: ${e.message}, falling back to Camera2...")
                camera1Manager.closeCamera()
                setupCameraCallbacks(camera2Manager)
                camera2Manager.openCamera()
                textureView?.let { camera2Manager.attachTextureView(it) }
                camera2Manager.startPreview()
                activeCamera = camera2Manager
            } else {
                throw e
            }
        }
    }

    /**
     * Stops camera and scanning.
     */
    fun stop() {
        activeCamera?.closeCamera()
        activeCamera = null
        coroutineScope.coroutineContext.cancelChildren()
    }

    /**
     * Releases all camera and coroutine resources.
     */
    fun release() {
        stop()
        coroutineScope.cancel()
    }

    /**
     * Toggles the flashlight.
     */
    fun toggleTorch(): Boolean {
        val cam = activeCamera ?: camera2Manager
        val nextState = !cam.getTorch()
        cam.setTorch(nextState)
        onTorchStatusChange?.invoke(nextState)
        return nextState
    }

    /**
     * Sets torch directly.
     */
    fun setTorch(enable: Boolean) {
        val cam = activeCamera ?: camera2Manager
        cam.setTorch(enable)
        onTorchStatusChange?.invoke(enable)
    }

    fun isTorchOn(): Boolean = (activeCamera ?: camera2Manager).getTorch()

    /**
     * Manual zoom level setting (1.0x to max).
     */
    fun setZoomRatio(ratio: Float) {
        (activeCamera ?: camera2Manager).setZoomRatio(ratio)
    }

    fun getZoomRatio(): Float = (activeCamera ?: camera2Manager).getCurrentZoomRatio()

    fun getMaxZoomRatio(): Float = (activeCamera ?: camera2Manager).getMaxZoomRatio()

    /**
     * Focus on coordinates.
     */
    fun focusOnArea(rect: Rect?) {
        (activeCamera ?: camera2Manager).focusOnArea(rect)
    }

    private fun processFrame(nv21: ByteArray, width: Int, height: Int) {
        // Drop frame if previous decoding is still running (preserves preview smoothness)
        if (!isDecoding.compareAndSet(false, true)) {
            return
        }

        coroutineScope.launch {
            try {
                val rotation = if (context is Activity) {
                    @Suppress("DEPRECATION")
                    context.windowManager.defaultDisplay.rotation
                } else {
                    0
                }

                val bundle = Bundle().apply {
                    putParcelable("Screen", Point(1080, 1920))
                    putParcelable("Rect", Rect(-1, -1, -1, -1))
                    putBoolean(DetailRect.PARSE_RESULT, true)
                }

                val rawResults: Array<s6>? = h4.a().decode(
                    nv21,
                    width,
                    height,
                    rotation,
                    scanFormat,
                    ObjectWrapper.wrap(bundle)
                )

                if (!rawResults.isNullOrEmpty()) {
                    val first = rawResults[0]
                    val cam = activeCamera

                    // Handle intelligent auto-zoom for distant or small barcodes
                    if (autoZoomEnabled && first.l() > 1.0f && System.currentTimeMillis() - lastZoomTime > 1000 && cam != null) {
                        lastZoomTime = System.currentTimeMillis()
                        val targetZoom = (cam.getCurrentZoomRatio() * first.l()).coerceAtMost(cam.getMaxZoomRatio())
                        cam.setZoomRatio(targetZoom)
                    }

                    val hmsScans = y6.a(rawResults)
                    if (hmsScans.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onScanResult?.invoke(hmsScans)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decode frame error", e)
            } finally {
                isDecoding.set(false)
            }
        }
    }
}
