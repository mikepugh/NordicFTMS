package com.nordicrower.app

import android.util.Log
import android.content.Context
import android.os.Build
import com.nettarion.hyperborea.core.AppLogger
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.io.File
import java.util.UUID

object RowerLog : AppLogger {
    private val entries = ArrayDeque<String>()
    @Volatile var state = "À l'arrêt"
    @Volatile var metrics = "Puissance : indisponible\nCadence : indisponible\nCoups : indisponibles"
    @Volatile var clients = 0
    @Volatile var notifications = 0L
    @Volatile var delivered = 0L
    @Volatile var notificationErrors = 0L
    @Volatile var discovery = "Aucune recherche effectuée"
    @Volatile var busy = false
    @Volatile var discoveryRunning = false
    private var journal: DiagnosticJournal? = null
    private val runId = UUID.randomUUID().toString().take(8)

    @Synchronized fun initialize(context: Context) {
        if (journal != null) return
        try {
            journal = DiagnosticJournal(File(context.filesDir, "diagnostics"))
            discovery = journal!!.read("discovery.txt").ifEmpty { discovery }
        } catch (e: Exception) { Log.e("NordicRower", "Persistent diagnostics unavailable", e) }
        i("Application", "run=$runId version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
            "Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} " +
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Crash", "Uncaught exception thread=${thread.name}", error)
            persistStatus()
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized private fun add(level: String, tag: String, message: String, error: Throwable? = null) {
        val sanitized = DiagnosticJournal.sanitize(message + (error?.let { "\n${it.stackTraceToString()}" } ?: ""))
        val text = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()) +
            " run=$runId $level $tag: $sanitized"
        entries.addLast(text)
        while (entries.size > 400) entries.removeFirst()
        Log.i("NordicRower", text)
        try { journal?.append(text) } catch (e: Exception) { Log.e("NordicRower", "Journal write failed", e) }
    }
    override fun d(tag: String, message: String) = add("DEBUG", tag, message)
    override fun i(tag: String, message: String) = add("INFO", tag, message)
    override fun w(tag: String, message: String) = add("WARN", tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) = add("ERROR", tag, message, throwable)
    @Synchronized private fun status() = "NordicRower ${BuildConfig.VERSION_NAME}; run=$runId\nState: $state\n$metrics\n" +
        "BLE clients=$clients; queued=$notifications; completed=$delivered; errors=$notificationErrors\n" +
        "Notification completion does not prove the receiving app interpreted the data.\n"
    @Synchronized fun persistStatus() {
        try { journal?.snapshot("status.txt", status()) } catch (e: Exception) { Log.e("NordicRower", "Status save failed", e) }
    }
    @Synchronized fun saveDiscovery(report: String) {
        discovery = DiagnosticJournal.sanitize(report)
        try { journal?.snapshot("discovery.txt", discovery) } catch (e: Exception) {
            discovery += "\nÉchec de l'enregistrement : ${e.message}"
            Log.e("NordicRower", "Discovery save failed", e)
        }
    }
    @Synchronized fun export() = status() + "\nDISCOVERY\n$discovery\nJOURNAL\n" +
        (journal?.history() ?: entries.joinToString("\n"))
    @Synchronized fun recent() = entries.toList().takeLast(10).joinToString("\n")
}
