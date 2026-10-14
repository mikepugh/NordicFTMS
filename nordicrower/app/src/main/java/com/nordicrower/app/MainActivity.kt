package com.nordicrower.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.*
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var bluetooth: TextView
    private lateinit var inventory: TextView
    private lateinit var preparation: TextView
    private lateinit var logs: TextView
    private lateinit var discovery: TextView
    private lateinit var connectButton: Button
    private lateinit var discoverButton: Button
    private lateinit var usb: UsbManager
    private lateinit var usbRecoveryPanel: LinearLayout
    private lateinit var usbRecoveryInstructions: TextView
    private var usbRecoveryDialog: AlertDialog? = null
    private var lastShownRecovery: UsbClaimRecovery? = null
    private val handler = Handler(Looper.getMainLooper())
    private var waitingDevice: UsbDevice? = null
    private var requestedAction = "connect"
    private var lastInventoryAt = 0L
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
            showUsbRecovery()
            if (SystemClock.elapsedRealtime() - lastInventoryAt >= 2000) showInventory()
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
        usbRecoveryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            usbRecoveryInstructions = TextView(this@MainActivity).apply {
                textSize = 18f; setPadding(0, 16, 0, 16)
            }
            addView(usbRecoveryInstructions)
            addView(Button(this@MainActivity).apply {
                text = "Réglages iFIT"; isAllCaps = false
                setOnClickListener { openWolfSettings() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Réessayer"; isAllCaps = false
                setOnClickListener { RowerLog.usbClaimRecovery?.let { requestAction(it.action) } }
            })
        }.also { layout.addView(it) }
        metrics = text(22f).apply { this.text = RowerLog.metrics }
        bluetooth = text()
        preparation = text(18f)
        inventory = text()
        fun button(label: String, icon: Int, action: () -> Unit): Button {
            return Button(this).apply {
                this.text = label; isAllCaps = false
                setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
                setOnClickListener { action() }
            }.also { layout.addView(it) }
        }
        button("Actualiser l'état du matériel", android.R.drawable.ic_popup_sync) { showInventory() }
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
    override fun onDestroy() {
        usbRecoveryDialog?.dismiss()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun showUsbRecovery() {
        val recovery = RowerLog.usbClaimRecovery
        usbRecoveryPanel.visibility = if (recovery != null && !RowerLog.busy) View.VISIBLE else View.GONE
        if (recovery == null) {
            usbRecoveryDialog?.dismiss()
            lastShownRecovery = null
            return
        }
        usbRecoveryInstructions.text = recovery.instructions
        if (RowerLog.busy || recovery === lastShownRecovery || isFinishing || isDestroyed) return
        lastShownRecovery = recovery
        val instructions = TextView(this).apply {
            text = recovery.instructions
            textSize = 18f
            setPadding(32, 16, 32, 16)
        }
        usbRecoveryDialog = AlertDialog.Builder(this).setTitle(recovery.title)
            .setView(ScrollView(this).apply { addView(instructions) })
            .setPositiveButton("Réglages iFIT") { _, _ -> openWolfSettings() }
            .setNeutralButton("Réessayer") { _, _ -> requestAction(recovery.action) }
            .setNegativeButton("Plus tard", null).show()
    }

    private fun openWolfSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${UsbClaimRecovery.WOLF_PACKAGE}")))
            RowerLog.i("Recovery", "Opened Wolf app details; user must choose Force stop manually")
        } catch (e: Exception) {
            RowerLog.e("Recovery", "Cannot open Wolf app details; manual Settings instructions remain visible", e)
            AlertDialog.Builder(this).setTitle("Ouvrir les réglages manuellement")
                .setMessage("Ouvrez Paramètres > Applications > iFIT/Wolf, puis appuyez sur « Forcer l'arrêt ». " +
                    "Revenez ensuite à NordicRower et appuyez sur « Réessayer ».")
                .setPositiveButton("Compris", null).show()
        }
    }

    private fun showInventory() {
        lastInventoryAt = SystemClock.elapsedRealtime()
        preparation.text = RowerHardware.readOwnership(this).summary
        inventory.text = usb.deviceList.values.joinToString("\n") {
            "USB VID=%04x PID=%04x, interfaces=%d, autorisé=%s".format(it.vendorId, it.productId,
                it.interfaceCount, usb.hasPermission(it))
        }.ifEmpty { "Aucun contrôleur USB détecté" }
    }
    private fun requestAction(action: String) {
        if (RowerLog.busy || waitingDevice != null) return
        requestedAction = action
        showInventory()
        val ownership = RowerHardware.readOwnership(this)
        ownership.block?.let { block ->
            RowerHardware.logInventory(usb)
            RowerLog.state = block.message
            RowerLog.w("Ownership", "PREFLIGHT_BLOCKED action=$action reason=${block.name} " +
                "glassConsoleInstalled=${ownership.glassConsoleInstalled} wolfEnabled=${ownership.wolfEnabled}; " +
                "no USB open or BLE start attempted")
            if (action == "discover") {
                val now = java.time.OffsetDateTime.now()
                RowerLog.saveDiscovery("NordicRower ${BuildConfig.VERSION_NAME}\nDiscovery started=$now\n" +
                    "STEP 1/6 $now Vérification de Wolf, des permissions et du matériel\n" +
                    "PREFLIGHT_BLOCKED reason=${block.name}; no controller queries sent\n" +
                    "DISCOVERY_INCOMPLETE ${block.message}; missing replies are UNKNOWN\n" +
                    "Discovery ended=$now completed=false\n")
            }
            RowerLog.persistStatus()
            AlertDialog.Builder(this).setTitle(block.message).setMessage(block.guidance)
                .setPositiveButton("Compris", null).show()
            return
        }
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
            .setMessage(if (probe) "Laissez le rameur à l'arrêt. Wolf/iFIT peut rester activé. " +
                "Le diagnostic lit les fonctions déclarées et les valeurs disponibles, sans changer la résistance, " +
                "les watts cibles ou l'état de la séance. Un rapport sera enregistré automatiquement, même en cas d'échec."
                else "Wolf/iFIT peut rester activé. NordicRower essaiera l'accès USB et initialisera une séance. " +
                    "Gardez NordicRower au premier plan pendant l'essai. Si l'interface USB est occupée, la connexion échouera. " +
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
