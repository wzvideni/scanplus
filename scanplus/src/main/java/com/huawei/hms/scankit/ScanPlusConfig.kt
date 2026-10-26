package com.huawei.hms.scankit

import android.util.Log
import com.huawei.hms.scankit.camera.CameraMode
import com.huawei.hms.scankit.util.ScanLog

/**
 * Global configuration options for ScanPlus.
 */
object ScanPlusConfig {
    private const val TAG = "ScanPlusConfig"
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
     * Optional manual override for Camera preview display orientation in degrees (e.g. 0, 90, 180, 270).
     *
     * By default (null), ScanPlus adheres 100% to ScanKit's original orientation contract (`e0.d()`,
     * which is 90 degrees in portrait mode matching the original scanplus-2.15.0.301.aar behavior).
     *
     * If set to a non-null value, this orientation value will override the hardware/system display orientation.
     */
    @JvmStatic
    var customDisplayOrientation: Int? = null

    /**
     * Whether to automatically adapt camera display orientation based on hardware sensor orientation,
     * device rotation, and lens facing (front/back) across both Camera1 and Camera2.
     *
     * Defaults to `false` to ensure 100% backward compatibility with original `scanplus-2.15.0.301.aar` contracts
     * (which always uses ScanKit's fixed 90-degree contract in portrait mode).
     *
     * When set to `true`, both Camera1 and Camera2 will dynamically calculate the upright display orientation
     * using the standard Android camera formula `(sensorOrientation - deviceRotation + 360) % 360`,
     * automatically correcting orientations on non-standard hardware (e.g. 0° sensor POS terminals or handheld scanners)
     * and responding to landscape/portrait screen rotations.
     */
    @JvmStatic
    var autoAdaptSensorOrientation: Boolean = false

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
    var logDelegate: ((priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? =
        null

    init {
        // Automatically silence Huawei HiAnalytics/HATool background logs by default
        try {
            com.huawei.hms.hatool.c.a(Int.MAX_VALUE)
            ScanLog.d(TAG, "Huawei HATool HiAnalytics background logs silenced successfully")
        } catch (e: Throwable) {
            ScanLog.w(TAG, "Failed to silence Huawei HATool HiAnalytics background logs in init: ${e.message}", e)
        }
    }

    /**
     * Completely disables all logs (including errors and warnings).
     */
    @JvmStatic
    fun disableAllLogs() {
        try {
            com.huawei.hms.hatool.c.a(Int.MAX_VALUE)
            ScanLog.d(TAG, "Huawei HATool background logs silenced successfully")
        } catch (e: Throwable) {
            ScanLog.w(TAG, "Failed to silence Huawei HATool background logs: ${e.message}", e)
        }
        try {
            com.huawei.hms.scankit.p.w7.a(false)
            ScanLog.d(TAG, "ScanKit internal engine logs (w7) disabled successfully")
        } catch (e: Throwable) {
            ScanLog.w(TAG, "Failed to disable ScanKit internal engine logs (w7): ${e.message}", e)
        }
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
        try {
            com.huawei.hms.scankit.p.w7.a(true)
            ScanLog.d(TAG, "ScanKit internal engine logs (w7) enabled successfully")
        } catch (e: Throwable) {
            ScanLog.w(TAG, "Failed to enable ScanKit internal engine logs (w7): ${e.message}", e)
        }
    }
}
