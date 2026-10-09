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
    private lateinit var discovery: TextView
    private lateinit var connectButton: Button
    private lateinit var discoverButton: Button
    private lateinit var usb: UsbManager
    private val handler = Handler(Looper.getMainLooper())
    private var waitingDevice: UsbDevice? = null
    private var requestedAction = "connect"
    private val refresh = object : Runnable {
        override fun run() {
            status.text = RowerLog.state
            metrics.text = RowerLog.metrics
            bluetooth.text = "Bluetooth : ${RowerLog.clients} connexion(s)\n" +
                "Notifications : ${RowerLog.notifications} envoyées, ${RowerLog.delivered} terminées, ${RowerLog.notificationErrors} erreurs"
            connectButton.isEnabled = !RowerLog.busy
            discoverButton.isEnabled = !RowerLog.busy
            discovery.text = discoverySummary()
            logs.text = RowerLog.recent()
            handler.postDelayed(this, 500)
        }
    }
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = waitingDevice ?: return
            waitingDevice = null
            if (usb.hasPermission(device)) confirmAction()
            else {
                RowerLog.state = "Autorisation USB refusée"
                RowerLog.w("Permissions", "USB permission denied action=$requestedAction")
                RowerLog.persistStatus()
            }
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
        status = text(19f).apply { this.text = RowerLog.state }
        metrics = text(22f).apply { this.text = RowerLog.metrics }
        bluetooth = text()
        inventory = text()
        fun button(label: String, icon: Int, action: () -> Unit): Button {
            return Button(this).apply {
                this.text = label; isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                setOnClickListener { action() }
            }.also { layout.addView(it) }
        }
        connectButton = button("Connecter le rameur", android.R.drawable.ic_media_play) { requestAction("connect") }
        button("Déconnecter", android.R.drawable.ic_media_pause) {
            startService(Intent(this, RowerService::class.java).setAction("stop"))
        }
        discoverButton = button("Rechercher les capacités", android.R.drawable.ic_menu_search) { requestAction("discover") }
        discovery = text(15f)
        button("Exporter les diagnostics", android.R.drawable.ic_menu_save) {
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
            "USB VID=%04x PID=%04x, interfaces=%d, autorisé=%s".format(it.vendorId, it.productId,
                it.interfaceCount, usb.hasPermission(it))
        }.ifEmpty { "Aucun contrôleur USB détecté" }
    }
    private fun requestAction(action: String) {
        requestedAction = action
        val permissions = if (action == "connect" && Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, 1); return
        }
        val targets = usb.deviceList.values.filter { it.vendorId == RowerHardware.VENDOR && it.productId in RowerHardware.PRODUCTS }
        if (targets.size != 1) {
            if (action == "discover") { confirmAction(); return }
            RowerLog.state = "Un contrôleur USB attendu ; ${targets.size} détecté(s)"
            RowerLog.w("Inventory", "Expected exactly one FitPro controller; found ${targets.size} action=$action")
            RowerLog.persistStatus()
            return
        }
        val device = targets.single()
        if (!usb.hasPermission(device)) {
            waitingDevice = device
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0,
                Intent("com.nordicrower.app.USB_PERMISSION").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE))
        } else confirmAction()
    }
    private fun confirmAction() {
        val action = requestedAction
        val probe = action == "discover"
        AlertDialog.Builder(this).setTitle(if (probe) "Rechercher les capacités du rameur ?" else "Connexion directe au rameur ?")
            .setMessage(if (probe) "Laissez le rameur à l'arrêt. Wolf/iFIT doit être désactivé. " +
                "Le diagnostic lit les fonctions déclarées et les valeurs disponibles, sans changer la résistance, " +
                "les watts cibles ou l'état de la séance. Un rapport sera enregistré automatiquement, même en cas d'échec."
                else "Wolf/iFIT doit être désactivé. NordicRower prendra la connexion USB et initialisera une séance. " +
                    "Aucune application ne sera désactivée automatiquement. Ce prototype n'est pas encore validé sur votre rameur.")
            .setNegativeButton("Annuler", null).setPositiveButton(if (probe) "Lancer le diagnostic" else "Connecter") { _, _ ->
                startForegroundService(Intent(this, RowerService::class.java).setAction(action))
            }.show()
    }
    private fun discoverySummary(): String {
        val report = RowerLog.discovery
        if (!report.contains("Discovery started=")) return "Aucune recherche effectuée"
        val lastStep = report.lineSequence().filter { it.startsWith("STEP ") }.lastOrNull()
            ?.substringAfter(" ")?.substringAfter(" ")?.substringAfter(" ") ?: "Diagnostic interrompu"
        fun feature(key: String): String {
            val value = report.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
            return when (value) {
                "DECLARED_NOT_WRITE_TESTED" -> "fonction déclarée, commande non testée"
                "NOT_DECLARED" -> "fonction non déclarée"
                else -> "inconnu"
            }
        }
        val outcome = when {
            RowerLog.discoveryRunning -> "Diagnostic en cours"
            report.contains("completed=true") -> "Diagnostic terminé"
            report.contains("completed=false") -> "Diagnostic incomplet"
            else -> "Diagnostic interrompu"
        }
        return "$outcome\n$lastStep\nRésistance : ${feature("resistanceControl")}\nERG natif : ${feature("nativeErg")}\n" +
            if (RowerLog.discoveryRunning) "" else "Récupérez les journaux avec collect-nordicrower-diagnostics.ps1 via ADB."
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) requestAction(requestedAction)
        else if (requestCode == 1) {
            RowerLog.state = "Autorisation Bluetooth refusée"
            RowerLog.w("Permissions", "Bluetooth permission denied")
            RowerLog.persistStatus()
        }
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
