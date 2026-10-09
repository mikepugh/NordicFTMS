package com.nordicrower.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import kotlinx.coroutines.*
import java.time.OffsetDateTime

class RowerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null
    private var session: FitProSession? = null
    private var ble: RowerBle? = null
    private var controllerScope: CoroutineScope? = null
    private var samples = 0L
    private var frames = 0L
    private var lastSampleLog = 0L
    private var lastDropReason: String? = null
    private var lastCapabilities: String? = null
    private var lastSampleAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            scope.launch { stopRun(); stopSelf() }
            return START_NOT_STICKY
        }
        if (run?.isActive == true) return START_NOT_STICKY
        RowerLog.busy = true
        val discoveryMode = intent?.action == "discover"
        RowerLog.discoveryRunning = discoveryMode
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rower", "Connexion au rameur", NotificationManager.IMPORTANCE_LOW))
            startForeground(1, Notification.Builder(this, "rower")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("NordicRower")
                .setContentText(if (discoveryMode) "Diagnostic des capacités" else "Connexion directe au contrôleur")
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)).build())
        } catch (e: Exception) {
            RowerLog.state = "Erreur : démarrage du service refusé"
            RowerLog.e("Service", "Foreground startup failed; controller and BLE were not opened", e)
            if (discoveryMode) RowerLog.saveDiscovery("NordicRower ${BuildConfig.VERSION_NAME}\n" +
                "Discovery started=${OffsetDateTime.now()}\n" +
                "DISCOVERY_INCOMPLETE ${e.javaClass.simpleName}: ${e.message}; no controller queries sent\n" +
                "Discovery ended=${OffsetDateTime.now()} completed=false\n")
            RowerLog.busy = false
            RowerLog.discoveryRunning = false
            RowerLog.persistStatus()
            stopSelf()
            return START_NOT_STICKY
        }
        run = scope.launch {
            try {
                if (discoveryMode) {
                    withContext(Dispatchers.IO) { discover() }
                    return@launch
                }
                samples = 0; frames = 0; lastSampleLog = 0; lastSampleAt = 0; lastDropReason = null; lastCapabilities = null
                RowerLog.notifications = 0; RowerLog.delivered = 0; RowerLog.notificationErrors = 0
                RowerLog.state = "Connexion au contrôleur USB"
                RowerLog.i("Session", "Begin direct-controller session; BLE targets are disabled")
                val started = System.nanoTime()
                controllerScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
                withContext(Dispatchers.IO) {
                    withTimeout(45_000) {
                        RowerHardware.connect(this@RowerService, controllerScope!!).also { session = it }
                    }
                }
                RowerLog.i("Session", "Handshake completed elapsedMs=${(System.nanoTime() - started) / 1_000_000} " +
                    "type=${session!!.detectedDeviceType} degraded=${session!!.degradedReason.value}")
                RowerLog.state = "Contrôleur connecté ; attente des données réelles"
                ble = RowerBle(this@RowerService, "NordicRower").also { it.start() }
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(5000)
                            RowerLog.i("Session", "Health samples=$samples completeFrames=$frames clients=${RowerLog.clients} " +
                                "queued=${RowerLog.notifications} completed=${RowerLog.delivered} errors=${RowerLog.notificationErrors} " +
                                "sampleAgeMs=${if (lastSampleAt == 0L) "UNKNOWN" else System.nanoTime() / 1_000_000 - lastSampleAt}")
                            if (frames == 0L) RowerLog.w("Telemetry", "No complete rower frame yet; watts AND real stroke rate/count are required")
                            RowerLog.persistStatus()
                        }
                    }
                    launch {
                        session!!.deviceIdentity.collect { identity ->
                            if (identity != null) RowerLog.i("Identity", "model=${identity.model} firmware=${identity.firmwareVersion} " +
                                "hardware=${identity.hardwareVersion} partNumber=${identity.partNumber}; serial omitted")
                        }
                    }
                    launch { session!!.degradedReason.collect { RowerLog.w("Session", "Degraded reason=$it") } }
                    launch {
                        session!!.exerciseData.collect { data ->
                            if (data != null) publish(data)
                            else {
                                RowerLog.w("Telemetry", "Live snapshot unavailable; not publishing cached values")
                                if (frames > 0) {
                                    RowerLog.state = "Flux de mesures interrompu"
                                    lastDropReason = "Live snapshot unavailable"
                                }
                            }
                        }
                    }
                    launch {
                        session!!.sessionState.collect { state ->
                            if (state is SessionState.Error) error(state.message)
                            if (state is SessionState.Disconnected) error("USB controller disconnected")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                RowerLog.state = "Erreur : délai de connexion dépassé"
                RowerLog.e("Session", "Handshake timed out; BLE was not started if controller handshake failed", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RowerLog.state = "Erreur : ${e.message}"
                RowerLog.e("Session", "Operation failed action=${if (discoveryMode) "discover" else "connect"}", e)
            } finally {
                withContext(NonCancellable) { release() }
                RowerLog.busy = false
                RowerLog.discoveryRunning = false
                RowerLog.persistStatus()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(data: ExerciseData) {
        samples++
        RowerLog.metrics = "Puissance : ${data.power ?: "indisponible"} W\n" +
            "Cadence : ${data.strokeRate ?: "indisponible"} coups/min\n" +
            "Coups : ${data.strokeCount ?: "indisponibles"}"
        val reason = RowerFrames.rejectionReason(data)
        if (reason != lastDropReason) {
            RowerLog.i("Telemetry", "Frame availability: ${reason ?: "COMPLETE"}")
            lastDropReason = reason
        }
        val now = System.nanoTime() / 1_000_000
        lastSampleAt = now
        if (now - lastSampleLog >= 2000) {
            lastSampleLog = now
            RowerLog.d("Telemetry", "sample=$samples watts=${data.power} strokeRate=${data.strokeRate} " +
                "strokeCount=${data.strokeCount} resistance=${data.resistance} receivedAtMs=${data.receivedAtMillis}")
            val capabilities = session?.deviceCapabilities.toString()
            if (capabilities != lastCapabilities) {
                lastCapabilities = capabilities
                RowerLog.i("Capabilities", "Controller limits=$capabilities; no write acceptance tested")
            }
        }
        RowerFrames.encode(data)?.let {
            frames++
            if (frames == 1L) {
                RowerLog.i("Telemetry", "First real complete 2AD1 frame=${it.joinToString(" ") { b -> "%02x".format(b.toInt() and 0xff) }}")
            }
            RowerLog.state = "Données réelles reçues ; Bluetooth NordicRower actif"
            ble?.publish(it)
        }
    }

    private suspend fun discover() {
        val report = StringBuilder("NordicRower ${BuildConfig.VERSION_NAME}\nDiscovery started=${OffsetDateTime.now()}\n" +
            "Query-only: no target, workout-state, security-unlock, calibration or firmware writes.\n" +
            "BLE control remains disabled. Serial numbers and Bluetooth addresses omitted.\n")
        fun record(line: String) {
            report.append(line).append('\n')
            RowerLog.i("Discovery", line)
            RowerLog.saveDiscovery(report.toString())
        }
        fun step(number: Int, text: String) {
            RowerLog.state = "Diagnostic $number/6 : $text"
            record("STEP $number/6 ${OffsetDateTime.now()} $text")
        }
        RowerLog.saveDiscovery(report.toString())
        var transport: com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport? = null
        var completed = false
        try {
            step(1, "Vérification de Wolf, des permissions et du matériel")
            val result = RowerHardware.createTransport(this)
            transport = result.transport
            record("USB vendor=0x213c product=${result.productId}")
            withTimeout(35_000) {
                RowerDiscovery(result.transport, result.productId, ::record, ::step).discover()
            }
            step(5, "Synthèse des fonctions déclarées")
            record("Read-only protocol discovery finished. Writes have NOT been tested.")
            completed = true
        } catch (e: Exception) {
            record("DISCOVERY_INCOMPLETE ${e.javaClass.simpleName}: ${e.message}; retain partial evidence; missing replies are UNKNOWN")
            throw e
        } finally {
            withContext(NonCancellable) {
                try { transport?.close() } catch (e: Exception) {
                    completed = false
                    record("USB_CLOSE_FAILED ${e.message}")
                }
                record("Discovery ended=${OffsetDateTime.now()} completed=$completed")
                if (completed) step(6, "Rapport enregistré ; récupérez-le avec le script ADB")
                else record("Diagnostic incomplet : récupérez également ce rapport avec le script ADB.")
                RowerLog.saveDiscovery(report.toString())
            }
        }
    }

    private suspend fun release() {
        try { ble?.close() } catch (e: Exception) { RowerLog.e("BLE", "Bluetooth release failed", e) }
        ble = null
        val old = session
        session = null
        if (old != null) withContext(Dispatchers.IO) {
            try { old.stop() } catch (e: Exception) { RowerLog.e("Session", "Controller release failed", e) }
        }
        controllerScope?.cancel()
        controllerScope = null
    }
    private suspend fun stopRun(updateState: Boolean = true) {
        run?.cancelAndJoin()
        run = null
        release()
        if (updateState) RowerLog.state = "À l'arrêt ; connexion USB libérée"
        RowerLog.busy = false
        RowerLog.discoveryRunning = false
        RowerLog.persistStatus()
    }
    override fun onDestroy() {
        scope.launch { stopRun(updateState = false); scope.cancel() }
        super.onDestroy()
    }
}
