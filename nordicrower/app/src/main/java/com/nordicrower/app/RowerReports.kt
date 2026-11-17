package com.nordicrower.app

import android.content.Context
import java.io.File

object RowerReports {
    /** Created only when the user requests an export; readable with ADB on release builds. */
    fun saveForAdb(context: Context): File {
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "diagnostics").apply { mkdirs() }
        return File(directory, "latest-report.txt").apply { writeText(RowerLog.export()) }
    }
}
