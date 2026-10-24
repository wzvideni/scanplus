package com.huawei.hms.scankit.p

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.TextureView
import com.huawei.hms.scankit.camera.Camera2Manager

/**
 * Modern Camera2 implementation of [j0], replacing the obsolete Camera1 API.
 * Fully compatible with ScanKit's capture engine (RemoteView, ScanKitActivity, CaptureHelper).
 */
class j0(
    private val context: Context,
    private val e0Var: e0
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    /**
     * Camera state enum matching ScanKit contract.
     * Entry b is renamed to CLOSED_STATE to avoid colliding with enum type name 'b' on K1 Kotlin compilers (< 2.0).
     * @JvmField val b in companion object guarantees Java bytecode exposes static field 'b' for backward compatibility.
     */
    enum class b(private val a: Int) {
        CLOSED_STATE(1),
        c(2),
        d(3),
        e(4),
        f(5);

        fun b(): Int = a

        companion object {
            @JvmField val CAMERA_CLOSED = CLOSED_STATE
            @JvmField val CAMERA_OPENED = c
            @JvmField val CAMERA_INITIALED = d
            @JvmField val PREVIEW_STARTED = e
            @JvmField val PREVIEW_STOPPED = f

            @JvmField val b = CLOSED_STATE
        }
    }

    /**
     * Camera status listener.
     */
    interface c {
        fun a() // onCameraOpened
        fun c() // onCameraClosed
        fun b() // onCameraError
    }

    /**
     * Frame callback delivering NV21 frame data to ScanKit's decode thread.
     */
    interface d {
        fun a(bArr: ByteArray?)
    }

    private var statusListener: c? = null
    private var frameCallback: d? = null
    private var currentState: b = b.CAMERA_CLOSED
    private var torchStatus: String = "off"
    private var textureView: TextureView? = null

    private val camera2Manager: Camera2Manager by lazy {
        val facing = if (e0Var.b() == 1) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        Camera2Manager(context, facing).apply {
            onCameraOpened = {
                synchronized(this@j0) {
                    if (currentState == b.CAMERA_CLOSED) {
                        currentState = b.CAMERA_OPENED
                    }
                    statusListener?.a()
                }
            }
            onCameraClosed = {
                synchronized(this@j0) {
                    currentState = b.CAMERA_CLOSED
                    statusListener?.c()
                }
            }
            onCameraError = { error ->
                Log.e(TAG, "Camera2 error: ${error.message}", error)
                synchronized(this@j0) {
                    statusListener?.b()
                }
            }
            onFrameAvailable = { nv21Bytes, _, _ ->
                synchronized(this@j0) {
                    if (currentState == b.PREVIEW_STARTED) {
                        frameCallback?.a(nv21Bytes)
                    }
                }
            }
        }
    }

    @Synchronized
    fun m() {
        Log.i(TAG, "Camera2 onResume")
        if (currentState == b.CAMERA_CLOSED || currentState == b.PREVIEW_STOPPED) {
            try {
                camera2Manager.openCamera()
                if (currentState.b() < b.CAMERA_OPENED.b()) {
                    currentState = b.CAMERA_OPENED
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera2 open failed: ${e.message}", e)
                statusListener?.b()
            }
        }
    }

    @Synchronized
    fun l() {
        Log.i(TAG, "Camera2 onPause")
        try {
            if (currentState.b() == b.PREVIEW_STARTED.b()) {
                a()
                q()
            }
            if (h() == "torch") {
                a("off")
            }
            if (currentState.b() >= b.CAMERA_OPENED.b()) {
                currentState = b.CAMERA_CLOSED
                camera2Manager.closeCamera()
                statusListener?.c()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera2 onPause error", e)
        }
    }

    @Synchronized
    fun k() {
        // onDestroy cleanup
        l()
    }

    @Synchronized
    fun h(): String = torchStatus

    @Synchronized
    fun a(str: String) {
        try {
            if (currentState.b() < b.CAMERA_OPENED.b()) return
            if ("torch" == str) {
                camera2Manager.setTorch(true)
                torchStatus = "torch"
            } else if ("off" == str) {
                camera2Manager.setTorch(false)
                torchStatus = "off"
            }
        } catch (e: Exception) {
            Log.w(TAG, "setTorchStatus error", e)
        }
    }

    @Synchronized
    fun p() {
        try {
            Log.i(TAG, "startPreview invoked, currentState=$currentState")
            if (currentState == b.CAMERA_CLOSED) {
                m()
            }
            camera2Manager.startPreview()
            currentState = b.PREVIEW_STARTED
        } catch (e: Exception) {
            Log.w(TAG, "startPreview error", e)
        }
    }

    @Synchronized
    fun q() {
        try {
            camera2Manager.stopPreview()
            currentState = b.PREVIEW_STOPPED
        } catch (e: Exception) {
            Log.w(TAG, "stopPreview error", e)
        }
    }

    @Synchronized
    fun o() {
        // Frame request trigger - in Camera2 frames stream continuously via ImageReader
    }

    @Synchronized
    fun a() {
        camera2Manager.cancelAutoFocus()
    }

    @Synchronized
    fun e(): Point = camera2Manager.getPreviewSize()

    @Synchronized
    fun n() {
        // Dummy callback - legacy Camera1 buffer allocation
    }

    @Synchronized
    fun j(): Boolean = true

    @Synchronized
    fun f(): b = currentState

    @Synchronized
    fun b(): g0 {
        val range = camera2Manager.getExposureRange()
        return g0(range.upper, range.lower, camera2Manager.getExposureCompensation(), camera2Manager.getExposureStep())
    }

    @Synchronized
    fun g(): m0 {
        val ratios = camera2Manager.getZoomRatios()
        return m0(ratios.size - 1, camera2Manager.getZoomIndex(), ratios)
    }

    @Synchronized
    fun c(): k0 {
        return k0(1, Rect(-100, -100, 100, 100))
    }

    @Synchronized
    fun d(): Int = e0Var.d()

    @Synchronized
    fun i(): Boolean = currentState.b() >= b.CAMERA_OPENED.b()

    @Synchronized
    @JvmName("b")
    fun setDisplayOrientation(i: Int) {
        e0Var.a(i)
    }

    @Synchronized
    fun c(i: Int) {
        if (currentState == b.CAMERA_CLOSED) return
        camera2Manager.setExposureCompensation(i)
    }

    @Synchronized
    fun d(i: Int) {
        if (currentState == b.CAMERA_CLOSED) return
        camera2Manager.setZoomIndex(i)
    }

    @Synchronized
    fun a(textureView: TextureView?) {
        requireNotNull(textureView) { "TextureView must not be null" }
        Log.i(TAG, "attachTextureView invoked, currentState=$currentState")
        this.textureView = textureView
        if (currentState == b.CAMERA_CLOSED) {
            m()
        }
        camera2Manager.attachTextureView(textureView)
        if (currentState.b() < b.CAMERA_INITIALED.b()) {
            currentState = b.CAMERA_INITIALED
        }
    }

    @Synchronized
    fun a(rect: Rect?, z: Boolean) {
        camera2Manager.focusOnArea(rect)
    }

    @Synchronized
    fun a(list: List<*>?) {
        if (list.isNullOrEmpty()) return
        val first = list[0]
        if (first is k0.a) {
            camera2Manager.focusOnArea(first.a)
        }
    }

    @Synchronized
    fun a(cVar: c?) {
        requireNotNull(cVar) { "Camera status listener cannot be null" }
        this.statusListener = cVar
    }

    @Synchronized
    fun a(dVar: d?) {
        requireNotNull(dVar) { "Frame callback cannot be null" }
        this.frameCallback = dVar
    }
}
