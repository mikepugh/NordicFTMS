package com.nordicrower.app

import android.util.Log
import com.nettarion.hyperborea.core.AppLogger
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object RowerLog : AppLogger {
    private val entries = ArrayDeque<String>()
    @Volatile var state = "Stopped"
    @Volatile var metrics = "Power: unavailable\nStroke rate: unavailable\nStrokes: unavailable"
    @Volatile var clients = 0
    @Volatile var notifications = 0L

    @Synchronized private fun add(level: String, tag: String, message: String, error: Throwable? = null) {
        val sanitized = message.replace(Regex("(?i)serial(?:number)?[=: ]+[^ ,;]+"), "serial=[omitted]")
        val text = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) +
            " $level $tag: $sanitized" + (error?.let { "; ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        entries.addLast(text)
        while (entries.size > 400) entries.removeFirst()
        Log.i("NordicRower", text)
    }
    override fun d(tag: String, message: String) = add("DEBUG", tag, message)
    override fun i(tag: String, message: String) = add("INFO", tag, message)
    override fun w(tag: String, message: String) = add("WARN", tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) = add("ERROR", tag, message, throwable)
    @Synchronized fun export() = "NordicRower ${BuildConfig.VERSION_NAME}\nState: $state\n$metrics\n" +
        "BLE clients: $clients; notifications queued: $notifications\n" + entries.joinToString("\n")
    @Synchronized fun recent() = entries.toList().takeLast(10).joinToString("\n")
}
