package com.example.twsbatterydemo.util

fun ByteArray.toHexString(separator: String = " "): String {
    return joinToString(separator) { "%02X".format(it) }
}

fun String.hexToByteArrayOrNull(): ByteArray? {
    val clean = replace(" ", "").replace("\n", "").replace("\t", "")
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return runCatching {
        ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()
}
