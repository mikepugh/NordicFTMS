package com.nordicrower.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Intercepts outbound UI intents: no email or other message is sent. */
class ReportExportUiTest {
    @Test fun shareAndSaveExportOnlyGeneratedRowerReport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("role") == "diagnostics")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val shared = AtomicReference<Intent>()
        val saved = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                when (intent.action) {
                    Intent.ACTION_CHOOSER -> {
                        @Suppress("DEPRECATION")
                        shared.set(intent.getParcelableExtra(Intent.EXTRA_INTENT))
                    }
                    Intent.ACTION_CREATE_DOCUMENT -> saved.set(intent)
                    else -> return null
                }
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        fun click(view: View, text: String): Boolean {
            if (view is Button && view.text.toString() == text) { view.performClick(); return true }
            return view is ViewGroup && (0 until view.childCount).any { click(view.getChildAt(it), text) }
        }
        instrumentation.addMonitor(monitor)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                assertTrue(click(activity.window.decorView, "Partager les diagnostics"))
            }
            val send = checkNotNull(shared.get())
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION")
            val uri = checkNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("com.nordicrower.app.reports", uri.authority)
            assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
            val report = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertTrue(report.startsWith("NordicRower ${BuildConfig.VERSION_NAME}; run="))
            assertTrue(report.contains("Exported:"))
            assertTrue(report.contains("JOURNAL"))
            instrumentation.runOnMainSync {
                assertTrue(click(activity.window.decorView, "Enregistrer les diagnostics"))
            }
            assertEquals("text/plain", saved.get()?.type)
            assertTrue(saved.get()?.getStringExtra(Intent.EXTRA_TITLE)?.startsWith("NordicRower-${BuildConfig.VERSION_NAME}-diagnostics-") == true)
            val adbReport = File(context.getExternalFilesDir(null), "diagnostics/latest-report.txt").readText()
            assertTrue(adbReport.startsWith("NordicRower ${BuildConfig.VERSION_NAME}; run="))
            assertTrue(adbReport.contains("Exported:"))
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
