package com.huawei.hms.scankit

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
}
