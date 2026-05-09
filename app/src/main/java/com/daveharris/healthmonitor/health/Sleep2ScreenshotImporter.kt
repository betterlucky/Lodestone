package com.daveharris.healthmonitor.health

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.daveharris.healthmonitor.util.GsonProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate

class Sleep2ScreenshotImporter(
    private val context: Context
) {
    fun importScreenshot(uri: Uri, targetDate: LocalDate): File {
        val dir = File(context.getExternalFilesDir(null), "analysis-sleep2/screenshots").apply { mkdirs() }
        val output = File(dir, "sleep2-statistics-$targetDate.png")
        val temp = File(dir, "${output.name}.tmp")

        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { outputStream ->
                input.copyTo(outputStream)
            }
        } ?: error("Unable to open Sleep2 screenshot.")

        if (output.exists() && !output.delete()) {
            temp.delete()
            error("Unable to replace existing Sleep2 screenshot.")
        }
        if (!temp.renameTo(output)) {
            temp.delete()
            error("Unable to save Sleep2 screenshot.")
        }

        val metadata = mapOf(
            "purpose" to "analysis_only_sleep2_h10_screenshot",
            "targetDate" to targetDate.toString(),
            "importedAt" to Instant.now().toString(),
            "originalDisplayName" to resolveDisplayName(uri),
            "savedFile" to output.absolutePath,
            "sizeBytes" to output.length()
        )
        File(dir, "sleep2-statistics-$targetDate.json").writeText(GsonProvider.gson.toJson(metadata))
        return output
    }

    private fun resolveDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}
