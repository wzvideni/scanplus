package com.huawei.hms.scankit.camera

import android.graphics.Point
import android.graphics.Rect
import android.util.Range
import android.view.TextureView

/**
 * Unified camera abstraction supporting both Camera1 and Camera2 backends.
 */
interface ICameraManager {
    var onFrameAvailable: ((ByteArray, Int, Int) -> Unit)?
    var onCameraOpened: (() -> Unit)?
    var onCameraClosed: (() -> Unit)?
    var onCameraError: ((Throwable) -> Unit)?

    fun attachTextureView(view: TextureView)
    fun openCamera()
    fun startPreview()
    fun stopPreview()
    fun closeCamera()
    fun requestPreviewFrame(mode: Int = 0)
    fun setTorch(enable: Boolean)
    fun getTorch(): Boolean
    fun setZoomIndex(index: Int)
    fun setZoomRatio(ratio: Float)
    fun getZoomIndex(): Int
    fun getZoomRatios(): List<Int>
    fun getMaxZoomRatio(): Float
    fun getCurrentZoomRatio(): Float
    fun focusOnArea(rect: Rect?)
    fun cancelAutoFocus()
    fun setMeteringAreas(areas: List<*>?)
    fun setExposureCompensation(value: Int)
    fun getExposureCompensation(): Int
    fun getExposureRange(): Range<Int>
    fun getExposureStep(): Float
    fun getPreviewSize(): Point
    fun getSensorOrientation(): Int
}
