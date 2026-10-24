package com.huawei.hms.scankit.camera

/**
 * Camera backend selection mode for ScanKit.
 */
enum class CameraMode {
    /**
     * Automatically selects the camera backend:
     * Attempts Camera1 (legacy) first; if Camera1 throws an exception or fails to connect,
     * seamlessly falls back to modern Camera2 to guarantee the highest device compatibility.
     */
    AUTO,

    /**
     * Enforces legacy Camera1 (android.hardware.Camera).
     */
    CAMERA1,

    /**
     * Enforces modern Camera2 (android.hardware.camera2).
     */
    CAMERA2
}
