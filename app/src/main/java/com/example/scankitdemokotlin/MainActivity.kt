package com.example.scankitdemokotlin

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.wzvideni.scanplus.R
import com.example.scankitdemokotlin.DefinedActivity.Companion.SCAN_RESULT
import com.huawei.hms.hmsscankit.ScanUtil
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScanAnalyzerOptions

class MainActivity : Activity(), ActivityCompat.OnRequestPermissionsResultCallback {

    companion object {
        private const val TAG = "MainActivity"
        const val CAMERA_REQ_CODE = 111
        const val DEFINED_CODE = 222
        const val BITMAP_CODE = 333
        const val MULTIPROCESSOR_SYN_CODE = 444
        const val MULTIPROCESSOR_ASYN_CODE = 555
        const val GENERATE_CODE = 666
        const val DECODE = 1
        const val GENERATE = 2
        const val REQUEST_CODE_SCAN_ONE = 0X01
        const val REQUEST_CODE_DEFINE = 0X0111
        const val REQUEST_CODE_SCAN_MULTI = 0X011
        const val DECODE_MODE = "decode_mode"
        const val RESULT = "SCAN_RESULT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable auto-adaptation for non-standard hardware (e.g. N6A1 with sensorOrientation = 0)
        // ScanPlusConfig.autoAdaptSensorOrientation is false by default for 100% legacy AAR compatibility.
        com.huawei.hms.scankit.ScanPlusConfig.autoAdaptSensorOrientation = true
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_mwcmain)
        this.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val window = window
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window?.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }

        // Programmatically bind all button click listeners to ensure 100% reliable trigger
        findViewById<View>(R.id.button_scan1)?.setOnClickListener { loadScanKitBtnClick(it) }
        findViewById<View>(R.id.button_scan3)?.setOnClickListener { newViewBtnClick(it) }
        findViewById<View>(R.id.button_scan4)?.setOnClickListener { bitmapBtnClick(it) }
        findViewById<View>(R.id.button_scan5)?.setOnClickListener { multiProcessorSynBtnClick(it) }
        findViewById<View>(R.id.button_scan6)?.setOnClickListener { multiProcessorAsynBtnClick(it) }
        findViewById<View>(R.id.button_scan7)?.setOnClickListener { generateQRCodeBtnClick(it) }
        findViewById<View>(R.id.button_scan8)?.setOnClickListener { composeScanBtnClick(it) }
    }

    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun loadScanKitBtnClick(view: View?) {
        Log.i(TAG, "loadScanKitBtnClick triggered")
        if (hasCameraPermission()) {
            ScanUtil.startScan(this, REQUEST_CODE_SCAN_ONE, HmsScanAnalyzerOptions.Creator().create())
        } else {
            requestPermission(CAMERA_REQ_CODE, DECODE)
        }
    }

    /**
     * Call the customized view.
     */
    fun newViewBtnClick(view: View?) {
        Log.i(TAG, "newViewBtnClick triggered")
        if (hasCameraPermission()) {
            val intent = Intent(this, DefinedActivity::class.java)
            this.startActivityForResult(intent, REQUEST_CODE_DEFINE)
        } else {
            requestPermission(DEFINED_CODE, DECODE)
        }
    }

    /**
     * Call the bitmap.
     */
    fun bitmapBtnClick(view: View?) {
        if (hasCameraPermission()) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, BITMAP_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        } else {
            requestPermission(BITMAP_CODE, DECODE)
        }
    }

    /**
     * Call the MultiProcessor API in synchronous mode.
     */
    fun multiProcessorSynBtnClick(view: View?) {
        if (hasCameraPermission()) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, MULTIPROCESSOR_SYN_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        } else {
            requestPermission(MULTIPROCESSOR_SYN_CODE, DECODE)
        }
    }

    /**
     * Call the MultiProcessor API in asynchronous mode.
     */
    fun multiProcessorAsynBtnClick(view: View?) {
        if (hasCameraPermission()) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, MULTIPROCESSOR_ASYN_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        } else {
            requestPermission(MULTIPROCESSOR_ASYN_CODE, DECODE)
        }
    }

    /**
     * Start generating the barcode. Directly launches GenerateCodeActivity without permission requirement.
     */
    fun generateQRCodeBtnClick(view: View?) {
        Log.i(TAG, "generateQRCodeBtnClick triggered, starting GenerateCodeActivity")
        startActivity(Intent(this, GenerateCodeActivity::class.java))
    }

    fun composeScanBtnClick(view: View?) {
        startActivity(Intent(this, com.wzvideni.scanplus.MainActivity::class.java))
    }

    /**
     * Apply for permissions.
     */
    private fun requestPermission(requestCode: Int, mode: Int) {
        if (mode == DECODE) {
            decodePermission(requestCode)
        }
    }

    /**
     * Apply for camera permission.
     */
    private fun decodePermission(requestCode: Int) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            requestCode
        )
    }

    /**
     * Call back the permission application result. If the permission application is successful, the barcode scanning view will be displayed.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Default View Mode
        if (requestCode == CAMERA_REQ_CODE) {
            ScanUtil.startScan(this, REQUEST_CODE_SCAN_ONE, HmsScanAnalyzerOptions.Creator().create())
        }
        // Customized View Mode
        if (requestCode == DEFINED_CODE) {
            val intent = Intent(this, DefinedActivity::class.java)
            this.startActivityForResult(intent, REQUEST_CODE_DEFINE)
        }
        // Bitmap Mode
        if (requestCode == BITMAP_CODE) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, BITMAP_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        }
        // Multiprocessor Synchronous Mode
        if (requestCode == MULTIPROCESSOR_SYN_CODE) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, MULTIPROCESSOR_SYN_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        }
        // Multiprocessor Asynchronous Mode
        if (requestCode == MULTIPROCESSOR_ASYN_CODE) {
            val intent = Intent(this, CommonActivity::class.java)
            intent.putExtra(DECODE_MODE, MULTIPROCESSOR_ASYN_CODE)
            this.startActivityForResult(intent, REQUEST_CODE_SCAN_MULTI)
        }
    }

    /**
     * Event for receiving the activity result.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            return
        }
        // Default View
        if (requestCode == REQUEST_CODE_SCAN_ONE) {
            val obj: HmsScan? = data.getParcelableExtra(ScanUtil.RESULT)
            if (obj != null) {
                val intent = Intent(this, DisPlayActivity::class.java)
                intent.putExtra(RESULT, obj)
                startActivity(intent)
            }
        // MultiProcessor & Bitmap
        } else if (requestCode == REQUEST_CODE_SCAN_MULTI) {
            val obj = data.getParcelableArrayExtra(SCAN_RESULT)
            if (obj != null && obj.isNotEmpty()) {
                if (obj.size == 1) {
                    if (obj[0] != null && !TextUtils.isEmpty((obj[0] as HmsScan).getOriginalValue())) {
                        val intent = Intent(this, DisPlayActivity::class.java)
                        intent.putExtra(RESULT, obj[0])
                        startActivity(intent)
                    }
                } else {
                    val intent = Intent(this, DisPlayMulActivity::class.java)
                    intent.putExtra(RESULT, obj)
                    startActivity(intent)
                }
            }
        // Customized View
        } else if (requestCode == REQUEST_CODE_DEFINE) {
            val obj: HmsScan? = data.getParcelableExtra(DefinedActivity.SCAN_RESULT)
            if (obj != null) {
                val intent = Intent(this, DisPlayActivity::class.java)
                intent.putExtra(RESULT, obj)
                startActivity(intent)
            }
        }
    }
}
