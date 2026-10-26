package com.huawei.hms.scankit.camera

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Surface
import com.huawei.hms.scankit.ScanPlusConfig

/**
 * Shared utility for calculating and resolving camera display orientation across
 * [Camera1Manager] and [Camera2Manager].
 */
object CameraOrientationUtils {

    /**
     * Calculates the device display orientation in degrees (0, 90, 180, 270)
     * using the standard Android Camera orientation formula.
     *
     * @param context Context used to query display rotation.
     * @param sensorOrientation Physical camera sensor mount orientation in degrees (e.g. 0, 90, 270).
     * @param isFront Whether the camera is facing front.
     * @return Display orientation in degrees (0, 90, 180, or 270).
     */
    @JvmStatic
    fun calculateDynamicOrientation(
        context: Context,
        sensorOrientation: Int,
        isFront: Boolean
    ): Int {
        val activity = context as? Activity
        val deviceRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        val deviceDegrees = when (deviceRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        return if (isFront) {
            val result = (sensorOrientation + deviceDegrees) % 360
            (360 - result) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    /**
     * Resolves the effective preview display orientation for both Camera1 and Camera2.
     *
     * Priority:
     * 1. [ScanPlusConfig.customDisplayOrientation]: Explicit developer override (highest priority).
     * 2. [ScanPlusConfig.autoAdaptSensorOrientation]: When enabled, dynamically calculates orientation
     *    using standard Android Camera formula ([calculateDynamicOrientation]) based on device rotation,
     *    hardware sensor orientation, and lens facing.
     * 3. [fallbackDisplayOrientation]: Default contract passed from ScanKit (`e0.d()`, 90 degrees by default
     *    in portrait), preserving 100% backward compatibility with original `scanplus-2.15.0.301.aar`.
     */
    @JvmStatic
    fun getEffectiveOrientation(
        context: Context,
        sensorOrientation: Int,
        isFront: Boolean,
        fallbackDisplayOrientation: Int
    ): Int {
        ScanPlusConfig.customDisplayOrientation?.let { return it }
        if (ScanPlusConfig.autoAdaptSensorOrientation) {
            return calculateDynamicOrientation(context, sensorOrientation, isFront)
        }
        return fallbackDisplayOrientation
    }
}
