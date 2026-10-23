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
import com.huawei.hms.scankit.camera.Camera2Manager
import com.huawei.hms.scankit.p.h4
import com.huawei.hms.scankit.p.s6
import com.huawei.hms.scankit.p.y6
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern, coroutine-based Camera2 barcode scanner manager in Kotlin.
 *
 * Connects directly to Android's Camera2 pipeline and Huawei ScanKit's native
 * recognition engine with automatic far-distance zoom and backpressure handling.
 */
class Camera2ScanManager(
    private val context: Context,
    private val scanFormat: Int = HmsScanBase.ALL_SCAN_TYPE,
    private val autoZoomEnabled: Boolean = true
) {
    companion object {
        private const val TAG = "Camera2ScanManager"
    }

    private val camera2Manager = Camera2Manager(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isDecoding = AtomicBoolean(false)
    private var lastZoomTime = 0L

    var onScanResult: ((Array<HmsScan>) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null
    var onTorchStatusChange: ((Boolean) -> Unit)? = null

    init {
        camera2Manager.onFrameAvailable = { nv21Bytes, width, height ->
            processFrame(nv21Bytes, width, height)
        }
        camera2Manager.onCameraError = { error ->
            onError?.invoke(error)
        }
    }

    /**
     * Attaches the preview TextureView.
     */
    fun attachPreview(textureView: TextureView) {
        camera2Manager.attachTextureView(textureView)
    }

    /**
     * Starts camera and barcode scanning.
     */
    fun start() {
        camera2Manager.openCamera()
        camera2Manager.startPreview()
    }

    /**
     * Stops camera and scanning.
     */
    fun stop() {
        camera2Manager.closeCamera()
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
        val nextState = !camera2Manager.getTorch()
        camera2Manager.setTorch(nextState)
        onTorchStatusChange?.invoke(nextState)
        return nextState
    }

    /**
     * Sets torch directly.
     */
    fun setTorch(enable: Boolean) {
        camera2Manager.setTorch(enable)
        onTorchStatusChange?.invoke(enable)
    }

    fun isTorchOn(): Boolean = camera2Manager.getTorch()

    /**
     * Manual zoom level setting (1.0x to max).
     */
    fun setZoomRatio(ratio: Float) {
        camera2Manager.setZoomRatio(ratio)
    }

    fun getZoomRatio(): Float = camera2Manager.getCurrentZoomRatio()

    fun getMaxZoomRatio(): Float = camera2Manager.getMaxZoomRatio()

    /**
     * Focus on coordinates.
     */
    fun focusOnArea(rect: Rect?) {
        camera2Manager.focusOnArea(rect)
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

                    // Handle intelligent auto-zoom for distant or small barcodes
                    if (autoZoomEnabled && first.l() > 1.0f && System.currentTimeMillis() - lastZoomTime > 1000) {
                        lastZoomTime = System.currentTimeMillis()
                        val targetZoom = (camera2Manager.getCurrentZoomRatio() * first.l()).coerceAtMost(camera2Manager.getMaxZoomRatio())
                        camera2Manager.setZoomRatio(targetZoom)
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
