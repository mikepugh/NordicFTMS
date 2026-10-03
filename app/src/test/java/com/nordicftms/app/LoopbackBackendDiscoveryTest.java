package com.nordicftms.app;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class LoopbackBackendDiscoveryTest {
    @Test public void onlyParsesLoopbackOrWildcardListeners() {
        assertEquals("127.0.0.1:54322", LoopbackBackendDiscovery.parseListener(row("0100007F:D432"), false).toString());
        assertEquals("127.0.0.1:443", LoopbackBackendDiscovery.parseListener(row("00000000:01BB"), false).toString());
        assertEquals("[::1]:54322", LoopbackBackendDiscovery.parseListener(
                row("00000000000000000000000001000000:D432"), true).toString());
        assertEquals("[::1]:54322", LoopbackBackendDiscovery.parseListener(
                row("00000000000000000000000000000000:D432"), true).toString());
        assertNull(LoopbackBackendDiscovery.parseListener(row("0101A8C0:D432"), false));
        assertNull(LoopbackBackendDiscovery.parseListener(row("0100007F:D432").replace("0A", "01"), false));
        assertNull(LoopbackBackendDiscovery.parseListener(row("0100007F:0000"), false));
        assertNull(LoopbackBackendDiscovery.parseListener(row("0100007F:10000"), false));
        assertNull(LoopbackBackendDiscovery.parseListener("sl local_address rem_address st", false));
        assertNull(LoopbackBackendDiscovery.parseListener("malformed", false));
    }

    @Test public void endpointRejectsExternalAndWildcardTargets() {
        for (String host : new String[]{"192.168.1.1", "example.com", "localhost", "0.0.0.0", "::"}) {
            try {
                new LoopbackBackendDiscovery.Endpoint(host, 54321);
                fail("Should reject " + host);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void readableTablesFindAlternatePortsWithoutAnActiveSweep() {
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery(
                (host, port, timeout) -> { throw new AssertionError("No sweep needed"); },
                ipv6 -> new StringReader(ipv6 ? "" : row("00000000:01BB")), () -> 100);
        LoopbackBackendDiscovery.Result result = discovery.discover();
        assertEquals("listener_tables_available", result.details.get("scan_outcome"));
        assertEquals(0, result.details.get("probes_this_pass"));
        assertTrue(result.candidates.contains(new LoopbackBackendDiscovery.Endpoint("127.0.0.1", 443)));
    }

    @Test public void blockedTablesUseTimeBudgetAndResumeAfterCooldown() {
        AtomicLong now = new AtomicLong(100);
        AtomicInteger calls = new AtomicInteger();
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery((host, port, timeout) -> {
            assertTrue("127.0.0.1".equals(host) || "::1".equals(host));
            assertTrue(timeout > 0 && timeout <= 25);
            now.incrementAndGet();
            calls.incrementAndGet();
            return Collections.singletonMap("outcome", port == 54322 ? "reachable" : "refused");
        }, ipv6 -> { throw new IOException("Permission denied"); }, now::get);
        LoopbackBackendDiscovery.Result first = discovery.discover();
        assertEquals("unavailable", first.details.get("tcp4_table"));
        assertEquals("partial", first.details.get("scan_outcome"));
        assertEquals((int) LoopbackBackendDiscovery.SCAN_BUDGET_MS, calls.get());
        assertTrue(first.candidates.contains(new LoopbackBackendDiscovery.Endpoint("127.0.0.1", 54322)));
        Object next = first.details.get("next_endpoint");
        assertEquals("cooldown", discovery.discover().details.get("scan_outcome"));
        assertEquals((int) LoopbackBackendDiscovery.SCAN_BUDGET_MS, calls.get());
        now.addAndGet(LoopbackBackendDiscovery.SCAN_COOLDOWN_MS);
        LoopbackBackendDiscovery.Result second = discovery.discover();
        assertNotEquals(next, second.details.get("next_endpoint"));
        assertEquals((int) LoopbackBackendDiscovery.SCAN_BUDGET_MS * 2, calls.get());
    }

    @Test public void capsProbesAndCoversEveryPortInBothFamiliesAcrossPasses() {
        AtomicLong now = new AtomicLong(0);
        Set<LoopbackBackendDiscovery.Endpoint> visited = new HashSet<>();
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery((host, port, timeout) -> {
            assertTrue("No duplicates within a sweep", visited.add(new LoopbackBackendDiscovery.Endpoint(host, port)));
            return Collections.singletonMap("outcome", "refused");
        }, ipv6 -> { throw new IOException("Not available"); }, now::get);
        LoopbackBackendDiscovery.Result result;
        do {
            result = discovery.discover();
            assertTrue((Integer) result.details.get("probes_this_pass") <= LoopbackBackendDiscovery.MAX_PROBES);
            now.addAndGet(LoopbackBackendDiscovery.SCAN_COOLDOWN_MS);
        } while ((Long) result.details.get("completed_sweeps") == 0);
        assertEquals(65535 * 2, visited.size());
        assertTrue(visited.contains(new LoopbackBackendDiscovery.Endpoint("::1", 1)));
        assertTrue(visited.contains(new LoopbackBackendDiscovery.Endpoint("127.0.0.1", 65535)));
        assertEquals("sweep_complete", result.details.get("scan_outcome"));
        assertEquals(0, result.details.get("scanned_in_sweep"));
    }

    @Test public void failedValidationIsRateLimitedAndDoesNotStarveOtherCandidates() {
        AtomicLong now = new AtomicLong(0);
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery(
                (host, port, timeout) -> { throw new AssertionError(); },
                ipv6 -> new StringReader(ipv6 ? "" : row("0100007F:D432")), now::get);
        List<LoopbackBackendDiscovery.Endpoint> initial = discovery.discover().candidates;
        LoopbackBackendDiscovery.Endpoint first = initial.get(0);
        discovery.validationAttempted(first);
        List<LoopbackBackendDiscovery.Endpoint> due = discovery.discover().candidates;
        assertFalse(due.contains(first));
        assertEquals(initial.get(1), due.get(0));
        now.set(LoopbackBackendDiscovery.VALIDATION_COOLDOWN_MS);
        due = discovery.discover().candidates;
        assertEquals(first, due.get(due.size() - 1));
    }

    @Test public void interruptionStopsScanningWithoutClearingInterruptFlag() {
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery(
                (host, port, timeout) -> { throw new AssertionError("Interrupted scan must not probe"); },
                ipv6 -> { throw new IOException("Not available"); }, () -> 0);
        try {
            Thread.currentThread().interrupt();
            LoopbackBackendDiscovery.Result result = discovery.discover();
            assertEquals("interrupted", result.details.get("scan_outcome"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void candidateMemoryIsBoundedAndLimitIsReported() {
        StringBuilder table = new StringBuilder();
        for (int port = 1; port <= 100; port++) table.append(row("0100007F:" + Integer.toHexString(port))).append('\n');
        LoopbackBackendDiscovery discovery = new LoopbackBackendDiscovery(
                (host, port, timeout) -> { throw new AssertionError(); },
                ipv6 -> new StringReader(ipv6 ? "" : table.toString()), () -> 0);
        LoopbackBackendDiscovery.Result result = discovery.discover();
        assertEquals(LoopbackBackendDiscovery.MAX_CANDIDATES, result.candidates.size());
        assertEquals(true, result.details.get("candidate_limit_reached"));
    }

    private static String row(String address) { return "  0: " + address + " 00000000:0000 0A 00000000:00000000"; }
}
