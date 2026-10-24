package com.huawei.hms.scankit.p

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Range
import android.view.TextureView
import com.huawei.hms.scankit.ScanPlusConfig
import com.huawei.hms.scankit.camera.Camera1Manager
import com.huawei.hms.scankit.camera.Camera2Manager
import com.huawei.hms.scankit.camera.CameraMode
import com.huawei.hms.scankit.camera.ICameraManager

/**
 * Universal Camera Manager for ScanKit, integrating both Camera1 and Camera2.
 * Supports [CameraMode.AUTO], [CameraMode.CAMERA1], and [CameraMode.CAMERA2].
 *
 * In [CameraMode.AUTO], it attempts Camera1 first for maximum compatibility; if Camera1 fails
 * on modern or vendor-restricted devices, it automatically falls back to modern Camera2.
 */
@Suppress("DEPRECATION")
class j0(
    private val context: Context,
    private val e0Var: e0
) {
    companion object {
        private const val TAG = "CameraManager"

        /**
         * Global default camera mode. Can also be set via [ScanPlusConfig.cameraMode].
         */
        @JvmStatic
        var defaultCameraMode: CameraMode
            get() = ScanPlusConfig.cameraMode
            set(value) {
                ScanPlusConfig.cameraMode = value
            }
    }

    /**
     * Camera backend mode for this instance. Defaults to [ScanPlusConfig.cameraMode].
     */
    var cameraMode: CameraMode = ScanPlusConfig.cameraMode

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
    private var torchStatus: String = e0Var.f() ?: "off"
    private var textureView: TextureView? = null

    private var activeManager: ICameraManager? = null

    private val camera1Manager: Camera1Manager by lazy {
        val facing = if (e0Var.b() == 1) {
            Camera.CameraInfo.CAMERA_FACING_FRONT
        } else {
            Camera.CameraInfo.CAMERA_FACING_BACK
        }
        Camera1Manager(
            context = context,
            lensFacing = facing,
            targetResolution = e0Var.a() ?: Point(Camera1Manager.DEFAULT_PREVIEW_WIDTH, Camera1Manager.DEFAULT_PREVIEW_HEIGHT),
            recordingHint = e0Var.e()
        )
    }

    private val camera2Manager: Camera2Manager by lazy {
        val facing = if (e0Var.b() == 1) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        Camera2Manager(context, facing)
    }

    private fun bindCallbacks(manager: ICameraManager, backendName: String) {
        manager.onCameraOpened = {
            synchronized(this@j0) {
                if (currentState == b.CAMERA_CLOSED) {
                    currentState = b.CAMERA_OPENED
                }
                statusListener?.a()
            }
        }
        manager.onCameraClosed = {
            synchronized(this@j0) {
                currentState = b.CAMERA_CLOSED
                statusListener?.c()
            }
        }
        manager.onCameraError = { error ->
            Log.e(TAG, "$backendName error: ${error.message}", error)
            synchronized(this@j0) {
                statusListener?.b()
            }
        }
        manager.onFrameAvailable = { nv21Bytes, _, _ ->
            synchronized(this@j0) {
                if (currentState == b.PREVIEW_STARTED) {
                    frameCallback?.a(nv21Bytes)
                }
            }
        }
    }

    @Synchronized
    fun m() {
        Log.i(TAG, "Camera onResume, mode=$cameraMode")
        if (currentState == b.CAMERA_CLOSED || currentState == b.PREVIEW_STOPPED) {
            when (cameraMode) {
                CameraMode.CAMERA1 -> {
                    try {
                        bindCallbacks(camera1Manager, "Camera1")
                        camera1Manager.openCamera()
                        activeManager = camera1Manager
                        if (currentState.b() < b.CAMERA_OPENED.b()) {
                            currentState = b.CAMERA_OPENED
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Camera1 open failed: ${e.message}", e)
                        statusListener?.b()
                    }
                }
                CameraMode.CAMERA2 -> {
                    try {
                        bindCallbacks(camera2Manager, "Camera2")
                        camera2Manager.openCamera()
                        activeManager = camera2Manager
                        if (currentState.b() < b.CAMERA_OPENED.b()) {
                            currentState = b.CAMERA_OPENED
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Camera2 open failed: ${e.message}", e)
                        statusListener?.b()
                    }
                }
                CameraMode.AUTO -> {
                    var c1Success = false
                    try {
                        Log.i(TAG, "AUTO mode: Trying Camera1 first...")
                        bindCallbacks(camera1Manager, "Camera1")
                        camera1Manager.openCamera()
                        activeManager = camera1Manager
                        if (currentState.b() < b.CAMERA_OPENED.b()) {
                            currentState = b.CAMERA_OPENED
                        }
                        c1Success = true
                        Log.i(TAG, "AUTO mode: Camera1 opened successfully")
                    } catch (e: Throwable) {
                        Log.w(TAG, "Camera1 open failed in AUTO mode: ${e.message}. Falling back to Camera2...")
                    }

                    if (!c1Success) {
                        try {
                            bindCallbacks(camera2Manager, "Camera2")
                            camera2Manager.openCamera()
                            activeManager = camera2Manager
                            if (currentState.b() < b.CAMERA_OPENED.b()) {
                                currentState = b.CAMERA_OPENED
                            }
                            Log.i(TAG, "AUTO mode: Camera2 fallback opened successfully")
                        } catch (e: Throwable) {
                            Log.e(TAG, "Camera2 fallback also failed: ${e.message}", e)
                            statusListener?.b()
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun l() {
        Log.i(TAG, "Camera onPause")
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
                activeManager?.closeCamera()
                activeManager = null
                statusListener?.c()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera onPause error", e)
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
                activeManager?.setTorch(true)
                torchStatus = "torch"
            } else if ("off" == str) {
                activeManager?.setTorch(false)
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
            val mgr = activeManager ?: return
            try {
                mgr.startPreview()
            } catch (e: Exception) {
                if (cameraMode == CameraMode.AUTO && mgr === camera1Manager) {
                    Log.w(TAG, "Camera1 startPreview failed in AUTO mode, falling back to Camera2: ${e.message}")
                    mgr.closeCamera()
                    bindCallbacks(camera2Manager, "Camera2")
                    camera2Manager.openCamera()
                    textureView?.let { camera2Manager.attachTextureView(it) }
                    camera2Manager.startPreview()
                    activeManager = camera2Manager
                } else {
                    throw e
                }
            }
            currentState = b.PREVIEW_STARTED
        } catch (e: Exception) {
            Log.w(TAG, "startPreview error", e)
        }
    }

    @Synchronized
    fun q() {
        try {
            activeManager?.stopPreview()
            currentState = b.PREVIEW_STOPPED
        } catch (e: Exception) {
            Log.w(TAG, "stopPreview error", e)
        }
    }

    @Synchronized
    fun o() {
        activeManager?.requestPreviewFrame(e0Var.c())
    }

    @Synchronized
    fun a() {
        activeManager?.cancelAutoFocus()
    }

    @Synchronized
    fun e(): Point = activeManager?.getPreviewSize() ?: Point(Camera1Manager.DEFAULT_PREVIEW_WIDTH, Camera1Manager.DEFAULT_PREVIEW_HEIGHT)

    @Synchronized
    fun n() {
        // Dummy callback - legacy Camera1 buffer allocation
    }

    @Synchronized
    fun j(): Boolean = (activeManager?.getZoomRatios()?.size ?: 0) > 1

    @Synchronized
    fun f(): b = currentState

    @Synchronized
    fun b(): g0? {
        val mgr = activeManager ?: return null
        if (currentState == b.CAMERA_CLOSED) return null
        val range = mgr.getExposureRange()
        return g0(
            range.upper,
            range.lower,
            mgr.getExposureCompensation(),
            mgr.getExposureStep()
        )
    }

    @Synchronized
    fun g(): m0? {
        val mgr = activeManager ?: return null
        if (currentState == b.CAMERA_CLOSED) return null
        val ratios = mgr.getZoomRatios()
        val currentIndex = mgr.getZoomIndex()
        return m0(ratios.size - 1, currentIndex, ratios)
    }

    @Synchronized
    fun c(): k0? {
        if (activeManager == null || currentState == b.CAMERA_CLOSED) return null
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
        val mgr = activeManager
        if (mgr is Camera1Manager) {
            mgr.setDisplayOrientation(i)
        }
    }

    @Synchronized
    fun c(i: Int) {
        if (currentState == b.CAMERA_CLOSED) return
        activeManager?.setExposureCompensation(i)
    }

    @Synchronized
    fun d(i: Int) {
        if (currentState == b.CAMERA_CLOSED) return
        activeManager?.setZoomIndex(i)
    }

    @Synchronized
    fun a(textureView: TextureView?) {
        requireNotNull(textureView) { "TextureView must not be null" }
        Log.i(TAG, "attachTextureView invoked, currentState=$currentState")
        this.textureView = textureView
        if (currentState == b.CAMERA_CLOSED) {
            m()
        }
        activeManager?.attachTextureView(textureView)
        if (currentState.b() < b.CAMERA_INITIALED.b()) {
            currentState = b.CAMERA_INITIALED
        }
    }

    @Synchronized
    fun a(rect: Rect?, z: Boolean) {
        activeManager?.focusOnArea(rect)
    }

    @Synchronized
    fun a(list: List<*>?) {
        if (list.isNullOrEmpty()) return
        activeManager?.setMeteringAreas(list)
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
