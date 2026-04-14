package com.example.twsbatterydemo.util

import android.os.ParcelUuid
import java.util.Locale

fun parcelUuidTo16Bit(parcelUuid: ParcelUuid): Int? {
    val uuid = parcelUuid.uuid.toString().lowercase(Locale.US)
    val suffix = "-0000-1000-8000-00805f9b34fb"
    if (!uuid.endsWith(suffix)) return null
    val first = uuid.substringBefore("-")
    if (first.length != 8) return null
    return first.substring(4, 8).toIntOrNull(16)
}
