package com.nordicrower.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticJournalTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun survivesNewInstanceAndKeepsUtf8() {
        DiagnosticJournal(directory.root).append("Diagnostic terminé")
        assertTrue(DiagnosticJournal(directory.root).history().contains("terminé"))
    }
    @Test fun rotationIsBoundedAndPreservesRecentHistory() {
        val journal = DiagnosticJournal(directory.root, maxBytes = 10)
        repeat(8) { journal.append("entry $it") }
        assertEquals(3, directory.root.listFiles()!!.size)
        assertTrue(journal.history().contains("entry 7"))
        assertFalse(journal.history().contains("entry 0"))
    }
    @Test fun snapshotReplacesOldPartialReportAtomically() {
        val journal = DiagnosticJournal(directory.root)
        journal.snapshot("discovery.txt", "partial")
        journal.snapshot("discovery.txt", "complete")
        assertEquals("complete", journal.read("discovery.txt"))
        assertFalse(File(directory.root, "discovery.txt.tmp").exists())
    }
    @Test fun invalidSnapshotPathIsRejected() {
        try { DiagnosticJournal(directory.root).snapshot("../escape.txt", "test"); fail() }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun identitiesInMessagesAndExceptionsAreRedacted() {
        val result = DiagnosticJournal.sanitize("serial=12345 serialNumber: 54321 Device serial: ABCD AA:BB:CC:DD:EE:FF")
        assertFalse(result.contains("12345")); assertFalse(result.contains("54321"))
        assertFalse(result.contains("ABCD")); assertFalse(result.contains("AA:BB"))
    }
}
