package com.huawei.hms.scankit.p

import com.huawei.hms.scankit.util.ScanLog

/**
 * ScanKit internal log interceptor.
 * Intercepts internal logging across 40+ classes in ScanKit core and routes them to [ScanLog].
 */
object o4 {
    @JvmStatic
    fun a(tag: String?, msg: String?) {
        ScanLog.d(tag, msg)
    }

    @JvmStatic
    fun a(tag: String?, subtag: String?, msg: String?) {
        ScanLog.i(tag, if (subtag.isNullOrEmpty()) msg else "[$subtag] $msg")
    }

    @JvmStatic
    fun d(tag: String?, msg: String?) {
        ScanLog.i(tag, msg)
    }

    @JvmStatic
    fun e(tag: String?, msg: String?) {
        ScanLog.w(tag, msg)
    }

    @JvmStatic
    fun a(tag: String?, msg: String?, tr: Throwable?) {
        ScanLog.w(tag, msg, tr)
    }

    @JvmStatic
    fun b(tag: String?, msg: String?) {
        ScanLog.e(tag, msg)
    }
}
