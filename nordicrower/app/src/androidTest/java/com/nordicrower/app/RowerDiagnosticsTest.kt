package com.nordicrower.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** No USB devices are opened by these diagnostics tests. */
class RowerDiagnosticsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val role = InstrumentationRegistry.getArguments().getString("role")

    @Test fun failedDiscoveryPersistsPartialEvidence() = runBlocking<Unit> {
        assumeTrue(role == "diagnostics")
        val context = instrumentation.targetContext
        val usb = context.getSystemService(android.hardware.usb.UsbManager::class.java)
        assumeTrue(usb.deviceList.values.none { it.vendorId == RowerHardware.VENDOR })
        val previousReport = RowerLog.discovery
        context.startForegroundService(Intent(context, RowerService::class.java).setAction("discover"))
        withTimeout(10_000) {
            while (RowerLog.discovery == previousReport || !RowerLog.discovery.contains("completed=false") || RowerLog.busy) delay(100)
        }
        val file = File(context.filesDir, "diagnostics/discovery.txt")
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("DISCOVERY_INCOMPLETE"))
        assertTrue(file.readText().contains("STEP 1/6"))
        assertTrue(File(context.filesDir, "diagnostics/journal.txt").length() > 0)
        assertEquals(0, RowerLog.clients)
    }

    @Test fun renderFrenchUiWithoutUnlockingTablet() {
        assumeTrue(role == "diagnostics")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            listOf(800 to 1280, 1280 to 800).forEach { (width, height) ->
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                content.draw(canvas)
                File(activity.filesDir, "ui-$width.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                fun checkChildren(view: View) {
                    if (view is TextView && view.text.isNotEmpty()) {
                        assertTrue("Text extends outside view: ${view.text}", view.layout?.let {
                            (0 until it.lineCount).all { line -> it.getLineWidth(line) <= view.width - view.paddingLeft - view.paddingRight + 2 }
                        } ?: true)
                    }
                    if (view is android.view.ViewGroup) (0 until view.childCount).forEach { checkChildren(view.getChildAt(it)) }
                }
                checkChildren(content)
                bitmap.recycle()
            }
            activity.finish()
        }
    }
}
