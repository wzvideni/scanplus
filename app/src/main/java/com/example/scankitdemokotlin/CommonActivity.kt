package com.example.scankitdemokotlin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.Animation.AnimationListener
import android.widget.ImageView
import android.widget.TextView
import com.example.scankitdemo.CameraOperation
import com.wzvideni.scanplus.R
import com.example.scankitdemo.draw.ScanResultView
import com.example.scankitdemokotlin.DefinedActivity.Companion.REQUEST_CODE_PHOTO
import com.example.scankitdemokotlin.DefinedActivity.Companion.SCAN_RESULT
import com.example.scankitdemokotlin.MainActivity.Companion.BITMAP_CODE
import com.example.scankitdemokotlin.MainActivity.Companion.DECODE_MODE
import com.example.scankitdemokotlin.MainActivity.Companion.MULTIPROCESSOR_ASYN_CODE
import com.example.scankitdemokotlin.MainActivity.Companion.MULTIPROCESSOR_SYN_CODE
import com.huawei.hms.hmsscankit.ScanUtil
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScanAnalyzer
import com.huawei.hms.ml.scan.HmsScanAnalyzerOptions
import com.huawei.hms.mlsdk.common.MLFrame
import java.util.*

class CommonActivity : Activity() {
    private var backBtn: ImageView? = null
    private var imgBtn: ImageView? = null
    private var mscanArs: ImageView? = null
    private var mscanTips: TextView? = null
    private var cameraPreview: TextureView? = null
    private var cameraOperation: CameraOperation? = null
    private var handler: CommonHandler? = null
    val defaultValue = -1
    var mode = defaultValue

    var isShow = false
    var scanResultView: ScanResultView? = null

    companion object {
        const val TAG = "commonActivity"
        const val SCAN_RESULT = "scanResult"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_common)
        mode = intent.getIntExtra(DECODE_MODE, defaultValue)

        mscanArs = findViewById(R.id.scan_ars)
        mscanTips = findViewById(R.id.scan_tip)
        if (mode == MULTIPROCESSOR_ASYN_CODE || mode == MULTIPROCESSOR_SYN_CODE) {
            mscanArs?.visibility = View.INVISIBLE
            mscanTips?.setText(R.string.scan_showresult)
            val disappearAnimation = AlphaAnimation(1f, 0f)
            disappearAnimation.setAnimationListener(object : AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    mscanTips?.visibility = View.GONE
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })
            disappearAnimation.duration = 3000
            mscanTips?.startAnimation(disappearAnimation)
        }

        cameraOperation = CameraOperation()
        val preview = findViewById<TextureView>(R.id.surfaceView)
        cameraPreview = preview

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                isShow = true
                initCamera()
                cameraOperation?.configureTransform(width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                cameraOperation?.configureTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                isShow = false
                cameraOperation?.close()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        isShow = false
        setBackOperation()
        setPictureScanOperation()

        scanResultView = findViewById(R.id.scan_result_view)
    }

    private fun setBackOperation() {
        backBtn = findViewById(R.id.back_img)
        backBtn?.setOnClickListener {
            if (mode == MULTIPROCESSOR_ASYN_CODE || mode == MULTIPROCESSOR_SYN_CODE) {
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    override fun onBackPressed() {
        if (mode == MULTIPROCESSOR_ASYN_CODE || mode == MULTIPROCESSOR_SYN_CODE) {
            setResult(RESULT_CANCELED)
        }
        super.onBackPressed()
    }

    private fun setPictureScanOperation() {
        imgBtn = findViewById(R.id.img_btn)
        imgBtn?.setOnClickListener {
            val pickIntent = Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            pickIntent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            this@CommonActivity.startActivityForResult(pickIntent, REQUEST_CODE_PHOTO)
        }
    }

    override fun onResume() {
        super.onResume()
        val tv = cameraPreview
        if (tv != null && tv.isAvailable) {
            initCamera()
            cameraOperation?.configureTransform(tv.width, tv.height)
        }
    }

    override fun onPause() {
        if (handler != null) {
            handler?.quit()
            handler = null
        }
        cameraOperation?.close()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun initCamera() {
        val tv = cameraPreview ?: return
        if (!tv.isAvailable) return
        try {
            cameraOperation!!.open(tv)
            if (handler == null) {
                handler = CommonHandler(this@CommonActivity, cameraOperation!!, mode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "initCamera error: ${e.message}", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null || requestCode != REQUEST_CODE_PHOTO) {
            return
        }
        try {
            // Image-based scanning mode
            if (mode == BITMAP_CODE) {
                decodeBitmap(MediaStore.Images.Media.getBitmap(this.contentResolver, data.data), HmsScan.ALL_SCAN_TYPE)
            } else if (mode == MULTIPROCESSOR_SYN_CODE) {
                decodeMultiSyn(MediaStore.Images.Media.getBitmap(this.contentResolver, data.data))
            } else if (mode == MULTIPROCESSOR_ASYN_CODE) {
                decodeMultiAsyn(MediaStore.Images.Media.getBitmap(this.contentResolver, data.data))
            }
        } catch (e: Exception) {
            Log.e(TAG, Objects.requireNonNull<String>(e.message))
        }
    }

    private fun decodeBitmap(bitmap: Bitmap, scanType: Int) {
        val hmsScans = ScanUtil.decodeWithBitmap(this@CommonActivity, bitmap, HmsScanAnalyzerOptions.Creator().setHmsScanTypes(scanType).setPhotoMode(true).create())
        if (hmsScans != null && hmsScans.size > 0 && hmsScans[0] != null && !TextUtils.isEmpty(hmsScans[0]!!.getOriginalValue())) {
            val intent = Intent()
            intent.putExtra(SCAN_RESULT, hmsScans)
            setResult(RESULT_OK, intent)
            finish()
        }
    }

    private fun decodeMultiAsyn(bitmap: Bitmap) {
        val image = MLFrame.fromBitmap(bitmap)
        val analyzer = HmsScanAnalyzer.Creator(this).setHmsScanTypes(HmsScan.ALL_SCAN_TYPE).create()
        analyzer.analyzInAsyn(image).addOnSuccessListener { hmsScans ->
            if (hmsScans != null && hmsScans.size > 0 && hmsScans[0] != null && !TextUtils.isEmpty(hmsScans[0]!!.getOriginalValue())) {
                val intent = Intent()
                intent.putExtra(SCAN_RESULT, hmsScans.toTypedArray())
                setResult(RESULT_OK, intent)
                finish()
            }
        }.addOnFailureListener { e -> Log.w(TAG, e) }
    }

    private fun decodeMultiSyn(bitmap: Bitmap) {
        val image = MLFrame.fromBitmap(bitmap)
        val analyzer = HmsScanAnalyzer.Creator(this).setHmsScanTypes(HmsScan.ALL_SCAN_TYPE).create()
        val result = analyzer.analyseFrame(image)
        if (result != null && result.size() > 0 && result.valueAt(0) != null && !TextUtils.isEmpty(result.valueAt(0)!!.getOriginalValue())) {
            val info = arrayOfNulls<HmsScan?>(result.size())
            for (index in 0 until result.size()) {
                info[index] = result.valueAt(index)
            }
            val intent = Intent()
            intent.putExtra(SCAN_RESULT, info)
            setResult(RESULT_OK, intent)
            finish()
        }
    }
}
