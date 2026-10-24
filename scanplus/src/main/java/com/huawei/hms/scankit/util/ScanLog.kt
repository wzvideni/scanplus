package com.huawei.hms.scankit.util

import android.util.Log
import com.huawei.hms.scankit.ScanPlusConfig

/**
 * Centralized logger for ScanPlus and ScanKit engine.
 * Respects [ScanPlusConfig.isLogEnabled], [ScanPlusConfig.minLogLevel], and [ScanPlusConfig.logDelegate].
 */
object ScanLog {

    @JvmStatic
    fun v(tag: String?, msg: String?) {
        log(Log.VERBOSE, tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String?, msg: String?) {
        log(Log.DEBUG, tag, msg, null)
    }

    @JvmStatic
    fun i(tag: String?, msg: String?) {
        log(Log.INFO, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable? = null) {
        log(Log.WARN, tag, msg, tr)
    }

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable? = null) {
        log(Log.ERROR, tag, msg, tr)
    }

    private fun log(priority: Int, tag: String?, msg: String?, tr: Throwable?) {
        // Drop log if logging is disabled and priority is below the configured threshold
        if (!ScanPlusConfig.isLogEnabled && priority < ScanPlusConfig.minLogLevel) {
            return
        }

        val safeTag = tag ?: "ScanPlus"
        val safeMsg = msg ?: ""

        val delegate = ScanPlusConfig.logDelegate
        if (delegate != null) {
            try {
                delegate(priority, safeTag, safeMsg, tr)
                return
            } catch (e: Throwable) {
                Log.w("ScanLog", "Custom logDelegate failed: ${e.message}, falling back to Logcat", e)
            }
        }

        when (priority) {
            Log.VERBOSE -> Log.v(safeTag, safeMsg, tr)
            Log.DEBUG -> Log.d(safeTag, safeMsg, tr)
            Log.INFO -> Log.i(safeTag, safeMsg, tr)
            Log.WARN -> if (tr != null) Log.w(safeTag, safeMsg, tr) else Log.w(safeTag, safeMsg)
            Log.ERROR -> if (tr != null) Log.e(safeTag, safeMsg, tr) else Log.e(safeTag, safeMsg)
        }
    }
}
