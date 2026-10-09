package com.nordicrower.app

import java.io.File

/** Small, bounded journal; each append is closed so process death preserves prior lines. */
class DiagnosticJournal(private val directory: File, private val maxBytes: Long = 1_048_576) {
    init { check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostics directory" } }

    @Synchronized fun append(line: String) {
        val current = File(directory, "journal.txt")
        if (current.length() + line.toByteArray(Charsets.UTF_8).size + 1 > maxBytes) {
            File(directory, "journal-2.txt").delete()
            File(directory, "journal-1.txt").takeIf { it.exists() }?.let {
                check(it.renameTo(File(directory, "journal-2.txt"))) { "Cannot rotate diagnostics" }
            }
            if (current.exists()) check(current.renameTo(File(directory, "journal-1.txt"))) { "Cannot rotate diagnostics" }
        }
        current.appendText(line + "\n", Charsets.UTF_8)
    }

    @Synchronized fun snapshot(name: String, text: String) {
        require(name in setOf("status.txt", "discovery.txt"))
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        check(temporary.renameTo(File(directory, name))) { "Cannot save $name" }
    }

    @Synchronized fun read(name: String): String = File(directory, name).takeIf { it.exists() }
        ?.readText(Charsets.UTF_8) ?: ""

    @Synchronized fun history(): String = listOf("journal-2.txt", "journal-1.txt", "journal.txt")
        .joinToString("\n") { read(it) }

    companion object {
        fun sanitize(text: String): String = text
            .replace(Regex("(?i)\\bserial(?:number)?\\s*[:=]\\s*[^ ,;\\n]+"), "serial=[omitted]")
            .replace(Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[bluetooth-address-omitted]")
    }
}
