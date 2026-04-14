package com.example.twsbatterydemo.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

data class LogExportResult(
    val displayName: String,
    val contentUri: String?,
    val sizeBytes: Long?,
    val message: String
)

object DownloadLogExporter {

    fun exportText(
        context: Context,
        displayName: String,
        content: String
    ): LogExportResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return LogExportResult(
                    displayName = displayName,
                    contentUri = null,
                    sizeBytes = null,
                    message = "导出失败: 无法创建 MediaStore 记录"
                )

            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(bytes)
                outputStream.flush()
            } ?: return LogExportResult(
                displayName = displayName,
                contentUri = uri.toString(),
                sizeBytes = null,
                message = "导出失败: 无法打开输出流"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, completeValues, null, null)
            }

            LogExportResult(
                displayName = displayName,
                contentUri = uri.toString(),
                sizeBytes = bytes.size.toLong(),
                message = "导出成功"
            )
        } catch (e: Exception) {
            LogExportResult(
                displayName = displayName,
                contentUri = null,
                sizeBytes = null,
                message = "导出失败: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}
