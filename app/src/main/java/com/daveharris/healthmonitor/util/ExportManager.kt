package com.daveharris.healthmonitor.util

import android.content.Context
import com.daveharris.healthmonitor.data.InspectorRow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportManager(private val context: Context) {
    fun exportInspectorRows(rows: List<InspectorRow>): Result<File> = runCatching {
        val directory = File(
            context.getExternalFilesDir("exports"),
            "json"
        ).apply { mkdirs() }
        val fileName = "probe-export-${
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
        }.json"
        val file = File(directory, fileName)
        val tempFile = File(directory, "$fileName.tmp")
        FileOutputStream(tempFile).use { stream ->
            stream.writer(Charsets.UTF_8).use { writer ->
                GsonProvider.gson.toJson(rows, writer)
            }
            runCatching { stream.fd.sync() }
        }
        if (!tempFile.renameTo(file)) {
            error("Failed to promote temporary export file to ${file.absolutePath}")
        }
        file
    }
}
