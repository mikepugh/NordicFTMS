package com.nordicftms.app;

import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

import static org.junit.Assert.*;

public class BackendPortProbeTest {
    @Test
    public void reportsReachableWithoutSendingCommands() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(2000);
            Map<String, Object> result = BackendPortProbe.probe("127.0.0.1", server.getLocalPort(), 500);
            assertEquals("reachable", result.get("outcome"));
            try (Socket accepted = server.accept()) {
                accepted.setSoTimeout(2000);
                assertEquals(-1, accepted.getInputStream().read());
            }
        }
    }

    @Test
    public void distinguishesRefusedPortFromRpcReadiness() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = server.getLocalPort();
        }
        Map<String, Object> result = BackendPortProbe.probe("127.0.0.1", port, 500);
        assertEquals("refused", result.get("outcome"));
        assertTrue(result.containsKey("error"));
        assertEquals(port, result.get("port"));
    }
}
