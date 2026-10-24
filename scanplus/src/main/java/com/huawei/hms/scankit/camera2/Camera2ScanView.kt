package com.huawei.hms.scankit.camera2

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.*
import android.widget.FrameLayout
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScanBase
import com.huawei.hms.scankit.ScanPlusConfig
import com.huawei.hms.scankit.camera.CameraMode

/**
 * Modern Android View combining Camera2 preview with an animated viewfinder overlay.
 */
class Camera2ScanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textureView = TextureView(context)
    private val overlayView = ScanOverlayView(context)
    private var scanManager: Camera2ScanManager? = null

    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var gestureDetector: GestureDetector? = null

    var onScanResult: ((Array<HmsScan>) -> Unit)? = null
        set(value) {
            field = value
            scanManager?.onScanResult = value
        }

    var cameraMode: CameraMode = ScanPlusConfig.cameraMode
        set(value) {
            field = value
            scanManager?.cameraMode = value
        }

    init {
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setupGestures()
        setupTextureListener()
    }

    private fun setupTextureListener() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                ensureManager().attachPreview(textureView)
                scanManager?.start()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                scanManager?.stop()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun ensureManager(): Camera2ScanManager {
        if (scanManager == null) {
            scanManager = Camera2ScanManager(context, HmsScanBase.ALL_SCAN_TYPE, true, cameraMode).also { mgr ->
                mgr.onScanResult = onScanResult
            }
        }
        return scanManager!!
    }

    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val mgr = scanManager ?: return false
                val current = mgr.getZoomRatio()
                val factor = detector.scaleFactor
                val target = current * factor
                mgr.setZoomRatio(target)
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val x = e.x.toInt()
                val y = e.y.toInt()
                val focusRect = Rect(x - 100, y - 100, x + 100, y + 100)
                scanManager?.focusOnArea(focusRect)
                return true
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleGestureDetector?.onTouchEvent(event) ?: false
        handled = (gestureDetector?.onTouchEvent(event) ?: false) || handled
        return handled || super.onTouchEvent(event)
    }

    fun startScan() {
        overlayView.startAnimation()
        if (textureView.isAvailable) {
            ensureManager().attachPreview(textureView)
            scanManager?.start()
        }
    }

    fun stopScan() {
        overlayView.stopAnimation()
        scanManager?.stop()
    }

    fun toggleTorch(): Boolean = scanManager?.toggleTorch() ?: false

    fun setTorch(enable: Boolean) {
        ensureManager().setTorch(enable)
    }

    fun isTorchOn(): Boolean = scanManager?.isTorchOn() ?: false

    fun release() {
        stopScan()
        scanManager?.release()
        scanManager = null
    }

    /**
     * Overlay view providing the semi-transparent frame and scanning laser animation.
     */
    private class ScanOverlayView(context: Context) : View(context) {
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#60000000")
            style = Paint.Style.FILL
        }

        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private var scanBoxRect = Rect()
        private var laserY = 0f
        private var animator: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val boxSize = (Math.min(w, h) * 0.7f).toInt()
            val left = (w - boxSize) / 2
            val top = (h - boxSize) / 2
            scanBoxRect = Rect(left, top, left + boxSize, top + boxSize)
            restartAnimation()
        }

        fun startAnimation() {
            if (animator == null && scanBoxRect.height() > 0) {
                restartAnimation()
            }
        }

        fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        private fun restartAnimation() {
            animator?.cancel()
            if (scanBoxRect.height() <= 0) return
            animator = ValueAnimator.ofFloat(scanBoxRect.top.toFloat(), scanBoxRect.bottom.toFloat()).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { va ->
                    laserY = va.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (scanBoxRect.width() <= 0) return

            // Dim surroundings
            canvas.drawRect(0f, 0f, w, scanBoxRect.top.toFloat(), maskPaint)
            canvas.drawRect(0f, scanBoxRect.top.toFloat(), scanBoxRect.left.toFloat(), scanBoxRect.bottom.toFloat(), maskPaint)
            canvas.drawRect(scanBoxRect.right.toFloat(), scanBoxRect.top.toFloat(), w, scanBoxRect.bottom.toFloat(), maskPaint)
            canvas.drawRect(0f, scanBoxRect.bottom.toFloat(), w, h, maskPaint)

            // Draw bounding corners
            val cornerLength = 40f
            val r = scanBoxRect
            // Top Left
            canvas.drawLine(r.left.toFloat(), r.top.toFloat(), r.left + cornerLength, r.top.toFloat(), framePaint)
            canvas.drawLine(r.left.toFloat(), r.top.toFloat(), r.left.toFloat(), r.top + cornerLength, framePaint)
            // Top Right
            canvas.drawLine(r.right.toFloat(), r.top.toFloat(), r.right - cornerLength, r.top.toFloat(), framePaint)
            canvas.drawLine(r.right.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top + cornerLength, framePaint)
            // Bottom Left
            canvas.drawLine(r.left.toFloat(), r.bottom.toFloat(), r.left + cornerLength, r.bottom.toFloat(), framePaint)
            canvas.drawLine(r.left.toFloat(), r.bottom.toFloat(), r.left.toFloat(), r.bottom - cornerLength, framePaint)
            // Bottom Right
            canvas.drawLine(r.right.toFloat(), r.bottom.toFloat(), r.right - cornerLength, r.bottom.toFloat(), framePaint)
            canvas.drawLine(r.right.toFloat(), r.bottom.toFloat(), r.right.toFloat(), r.bottom - cornerLength, framePaint)

            // Draw animated laser line
            canvas.drawLine(r.left.toFloat(), laserY, r.right.toFloat(), laserY, laserPaint)
        }
    }
}
