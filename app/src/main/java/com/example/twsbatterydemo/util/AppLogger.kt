package com.example.twsbatterydemo.util

import android.util.Log

object AppLogger {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable? = null) {
        Log.e(tag, message, tr)
    }
}
