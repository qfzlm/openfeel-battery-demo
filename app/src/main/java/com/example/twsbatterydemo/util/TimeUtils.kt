package com.example.twsbatterydemo.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeUtils {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun format(timestamp: Long): String {
        return formatter.format(Date(timestamp))
    }
}
