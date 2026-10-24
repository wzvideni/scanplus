package com.huawei.hms.scankit

import android.util.Log
import com.huawei.hms.scankit.camera.CameraMode

/**
 * Global configuration options for ScanPlus.
 */
object ScanPlusConfig {
    /**
     * Selected camera backend mode:
     * - [CameraMode.AUTO] (Default): Automatically tries Camera1 first; if Camera1 fails to open or is
     *   unsupported by the hardware, automatically and seamlessly falls back to modern Camera2.
     * - [CameraMode.CAMERA1]: Strictly enforces legacy Camera1 (android.hardware.Camera).
     * - [CameraMode.CAMERA2]: Strictly enforces modern Camera2 (android.hardware.camera2).
     */
    @JvmStatic
    var cameraMode: CameraMode = CameraMode.AUTO

    /**
     * Controls whether verbose debug and info logging is enabled.
     * Default: false (silences high-frequency camera, preview, and decode logs).
     * Set to true to output verbose diagnostic logs in Logcat.
     */
    @JvmStatic
    var isLogEnabled: Boolean = false

    /**
     * Minimum log priority level to print when [isLogEnabled] is false.
     * Use standard Android Log priorities: [Log.VERBOSE], [Log.DEBUG], [Log.INFO], [Log.WARN], [Log.ERROR].
     * Defaults to [Log.WARN] (only warnings and errors are output by default).
     * Set to [Int.MAX_VALUE] to silence ALL logs including errors.
     */
    @JvmStatic
    var minLogLevel: Int = Log.WARN

    /**
     * Custom log sink callback. When set, all ScanPlus and ScanKit engine logs will be routed
     * to this callback instead of directly calling [android.util.Log].
     */
    @JvmStatic
    var logDelegate: ((priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    /**
     * Completely disables all logs (including errors and warnings).
     */
    @JvmStatic
    fun disableAllLogs() {
        isLogEnabled = false
        minLogLevel = Int.MAX_VALUE
    }

    /**
     * Enables full verbose diagnostic logging (VERBOSE, DEBUG, INFO, WARN, ERROR).
     */
    @JvmStatic
    fun enableAllLogs() {
        isLogEnabled = true
        minLogLevel = Log.VERBOSE
    }
}
