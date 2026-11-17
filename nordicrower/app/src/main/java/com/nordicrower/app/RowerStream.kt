package com.nordicrower.app

/** Presentation uses monotonic time; old measurements must never look live. */
data class RowerReading(val watts: Int, val strokeRate: Int, val strokes: Int, val atMillis: Long)

object RowerStream {
    const val STALE_AFTER_MS = 5000L

    fun fresh(reading: RowerReading?, nowMillis: Long): Boolean =
        reading != null && nowMillis - reading.atMillis in 0..STALE_AFTER_MS

    fun status(reading: RowerReading?, nowMillis: Long, clients: Int, subscribers: Int): String = when {
        reading == null -> "Rameur connecté ; ramez pour recevoir les premières mesures"
        !fresh(reading, nowMillis) -> "Mesures interrompues ; vérifiez la connexion au rameur"
        clients == 0 -> "Mesures reçues ; en attente d'une application Bluetooth"
        subscribers == 0 -> "Application connectée ; en attente de sa demande de mesures"
        else -> "Mesures reçues ; partage Bluetooth actif"
    }

    fun metrics(reading: RowerReading?, nowMillis: Long): String =
        if (fresh(reading, nowMillis)) {
            "Puissance : ${reading!!.watts} W\nCadence : ${reading.strokeRate} coups/min\nCoups : ${reading.strokes}"
        } else "Puissance : — W\nCadence : — coups/min\nCoups : —"
}
