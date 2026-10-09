package com.nordicrower.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.*
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var bluetooth: TextView
    private lateinit var inventory: TextView
    private lateinit var logs: TextView
    private lateinit var usb: UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private var waitingDevice: UsbDevice? = null
    private val refresh = object : Runnable {
        override fun run() {
            status.text = RowerLog.state
            metrics.text = RowerLog.metrics
            bluetooth.text = "Bluetooth clients: ${RowerLog.clients}    Notifications queued: ${RowerLog.notifications}"
            logs.text = RowerLog.recent()
            handler.postDelayed(this, 500)
        }
    }
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = waitingDevice ?: return
            waitingDevice = null
            if (usb.hasPermission(device)) confirmConnect()
            else RowerLog.state = "USB permission denied"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(UsbManager::class.java)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        fun text(size: Float = 16f) = TextView(this).apply {
            textSize = size; setPadding(0, 12, 0, 12); layout.addView(this)
        }
        text(26f).text = "NordicRower"
        text(14f).text = BuildConfig.VERSION_NAME
        status = text(19f)
        metrics = text(22f)
        bluetooth = text()
        inventory = text()
        fun button(label: String, icon: Int, action: () -> Unit) {
            layout.addView(Button(this).apply {
                this.text = label; isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                setOnClickListener { action() }
            })
        }
        button("Connect Rower", android.R.drawable.ic_media_play) { requestConnect() }
        button("Disconnect", android.R.drawable.ic_media_pause) {
            startService(Intent(this, RowerService::class.java).setAction("stop"))
        }
        button("Save Diagnostics", android.R.drawable.ic_menu_save) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "NordicRower-diagnostics.txt"), 2)
        }
        logs = text(13f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        setContentView(ScrollView(this).apply { addView(layout) })
        val filter = IntentFilter("com.nordicrower.app.USB_PERMISSION")
        ContextCompat.registerReceiver(this, permissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        showInventory()
    }
    override fun onResume() { super.onResume(); handler.post(refresh); showInventory() }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { unregisterReceiver(permissionReceiver); super.onDestroy() }

    private fun showInventory() {
        inventory.text = usb.deviceList.values.joinToString("\n") {
            "USB VID=%04x PID=%04x, interfaces=%d, access=%s".format(it.vendorId, it.productId,
                it.interfaceCount, usb.hasPermission(it))
        }.ifEmpty { "No USB controller detected" }
    }
    private fun requestConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, 1); return
        }
        val targets = usb.deviceList.values.filter { it.vendorId == RowerHardware.VENDOR && it.productId in RowerHardware.PRODUCTS }
        if (targets.size != 1) { RowerLog.state = "Expected one FitPro rower controller; found ${targets.size}"; return }
        val device = targets.single()
        if (!usb.hasPermission(device)) {
            waitingDevice = device
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                Intent("com.nordicrower.app.USB_PERMISSION").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE))
        } else confirmConnect()
    }
    private fun confirmConnect() {
        AlertDialog.Builder(this).setTitle("Start Direct Rower Session?")
            .setMessage("Wolf/iFIT must be disabled first. NordicRower will claim the USB controller and initialize a rower workout. No apps will be disabled automatically. This prototype has not been tested on your rower.")
            .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ ->
                startForegroundService(Intent(this, RowerService::class.java))
            }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) requestConnect()
    }
    @Deprecated("Android platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(RowerLog.export().toByteArray()) }
            } catch (e: Exception) { RowerLog.e("Diagnostics", "Export failed", e) }
        }
    }
}
