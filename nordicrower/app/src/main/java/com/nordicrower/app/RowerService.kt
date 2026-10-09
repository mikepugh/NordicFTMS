package com.nordicrower.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import kotlinx.coroutines.*

class RowerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var run: Job? = null
    private var session: FitProSession? = null
    private var ble: RowerBle? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            scope.launch { stopRun(); stopSelf() }
            return START_NOT_STICKY
        }
        if (run?.isActive == true) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rower", "Rower connection", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "rower")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NordicRower")
            .setContentText("Direct rower controller session")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)).build())
        run = scope.launch {
            try {
                RowerLog.state = "Connecting to USB controller"
                withContext(Dispatchers.IO) {
                    RowerHardware.connect(this@RowerService, scope).also { session = it }
                }
                RowerLog.state = "Controller connected; waiting for real stroke/power data"
                ble = RowerBle(this@RowerService, "NordicRower").also { it.start() }
                coroutineScope {
                    launch {
                        session!!.exerciseData.collect { data ->
                            if (data != null) publish(data)
                        }
                    }
                    launch {
                        session!!.sessionState.collect { state ->
                            if (state is SessionState.Error) error(state.message)
                            if (state is SessionState.Disconnected) error("USB controller disconnected")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RowerLog.state = "Error: ${e.message}"
                RowerLog.e("Session", "Connection failed", e)
            } finally {
                withContext(NonCancellable) { release() }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun publish(data: ExerciseData) {
        RowerLog.metrics = "Power: ${data.power ?: "unavailable"} W\n" +
            "Stroke rate: ${data.strokeRate ?: "unavailable"} strokes/min\n" +
            "Strokes: ${data.strokeCount ?: "unavailable"}"
        RowerFrames.encode(data)?.let { ble?.publish(it) }
    }

    private suspend fun release() {
        try { ble?.close() } catch (e: Exception) { RowerLog.e("BLE", "Bluetooth release failed", e) }
        ble = null
        val old = session
        session = null
        if (old != null) withContext(Dispatchers.IO) {
            try { old.stop() } catch (e: Exception) { RowerLog.e("Session", "Controller release failed", e) }
        }
    }
    private suspend fun stopRun(updateState: Boolean = true) {
        run?.cancelAndJoin()
        run = null
        release()
        if (updateState) RowerLog.state = "Stopped; USB released"
    }
    override fun onDestroy() {
        scope.launch { stopRun(updateState = false); scope.cancel() }
        super.onDestroy()
    }
}
