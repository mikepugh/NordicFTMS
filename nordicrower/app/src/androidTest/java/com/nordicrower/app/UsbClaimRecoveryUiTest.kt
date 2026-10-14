package com.nordicrower.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Simulates only the recovery state. No USB access or Wolf process changes occur. */
class UsbClaimRecoveryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun node(text: String, root: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString()?.equals(text, ignoreCase = true) == true) return root
        for (index in 0 until root.childCount) node(text, root.getChild(index))?.let { return it }
        return null
    }

    private fun awaitNode(text: String): AccessibilityNodeInfo = runBlocking {
        try { withTimeout(5000) {
            while (true) {
                node(text)?.let { return@withTimeout it }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Missing UI text: $text")
        } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Missing UI text: $text", e)
        }
    }

    @Test fun claimFailureOffersSettingsAndPreservesDiscoveryRetry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("role") == "diagnostics")
        val context = instrumentation.targetContext
        assumeTrue(context.getSystemService(android.hardware.usb.UsbManager::class.java).deviceList.isEmpty())
        val recovery = UsbClaimRecovery("discover")
        val openedIntent = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) return null
                openedIntent.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        var activity: Activity? = null
        instrumentation.addMonitor(monitor)
        try {
            instrumentation.runOnMainSync {
                RowerLog.busy = false
                RowerLog.usbClaimRecovery = recovery
                RowerLog.persistStatus()
            }
            assertTrue(File(context.filesDir, "diagnostics/status.txt").readText().contains("USB_RECOVERY_ACTION=discover"))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitNode(recovery.title)
            awaitNode(recovery.instructions)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "usb-recovery-dialog.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
            }
            assertTrue(awaitNode("Réglages iFIT").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, openedIntent.get()?.action)
            assertEquals("package:com.ifit.standalone", openedIntent.get()?.dataString)
            // After leaving the dialog, the main screen retains the return/retry instructions.
            awaitNode(recovery.instructions)
            assertEquals(recovery, RowerLog.usbClaimRecovery)
            assertTrue(awaitNode("Réessayer").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode("Rechercher les capacités du rameur ?")
            assertTrue(awaitNode("Annuler").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(recovery, RowerLog.usbClaimRecovery)
            // A new activity also presents the unresolved recovery, without opening USB.
            instrumentation.runOnMainSync { activity?.finish() }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitNode(recovery.title)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                activity?.finish()
                RowerLog.usbClaimRecovery = null
                RowerLog.persistStatus()
            }
        }
    }
}
