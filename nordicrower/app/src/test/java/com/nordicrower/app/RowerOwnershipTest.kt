package com.nordicrower.app

import org.junit.Assert.*
import org.junit.Test

class RowerOwnershipTest {
    @Test fun enabledWolfAllowsInterfaceClaimToBeAttempted() {
        val ownership = RowerOwnership(false, true)
        assertNull(ownership.block)
        assertTrue(ownership.summary.contains("essai en parallèle"))
        assertTrue(ownership.summary.contains("accès USB à vérifier"))
    }

    @Test fun disabledWolfAllowsInterfaceClaimToBeAttempted() {
        val ownership = RowerOwnership(false, false)
        assertNull(ownership.block)
        assertTrue(ownership.summary.contains("accès USB à vérifier"))
    }

    @Test fun absentWolfAllowsInterfaceClaimToBeAttempted() {
        assertNull(RowerOwnership(false, null).block)
    }

    @Test fun glassConsoleIsRejectedRegardlessOfWolfState() {
        listOf(true, false, null).forEach { wolfEnabled ->
            assertEquals(RowerOwnershipBlock.GLASS_CONSOLE, RowerOwnership(true, wolfEnabled).block)
        }
    }
}
