package com.nordicftms.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * DIRCON (Wahoo Direct Connect) server that exposes the treadmill as an FTMS
 * device over TCP/IP. This allows apps like Zwift, Rouvy, and PowerTread to
 * connect over WiFi instead of Bluetooth for a more stable connection.
 *
 * The DIRCON protocol tunnels BLE GATT operations (service/characteristic
 * discovery, reads, writes, notifications) over a TCP socket with a 6-byte
 * header framing protocol.
 */
public class DirconServer {
    private static final String LOG_TAG = "DIRCON";
    private static final String GENERIC_DIRCON_SERVICE_NAME = "NordicFTMS";
    private static final String GENERIC_MANUFACTURER_NAME = "NordicFTMS";
    private static final String GENERIC_HARDWARE_REV = "1";
    private static final String KICKR_RUN_HARDWARE_REV = "4";
    private static final String KICKR_RUN_FIRMWARE_REV = "1.6.17";

    // DIRCON protocol constants
    private static final int DIRCON_PORT = 36866;
    private static final byte MSG_VERSION = 0x01;
    private static final byte MSG_DISCOVER_SERVICES = 0x01;
    private static final byte MSG_DISCOVER_CHARACTERISTICS = 0x02;
    private static final byte MSG_READ_CHARACTERISTIC = 0x03;
    private static final byte MSG_WRITE_CHARACTERISTIC = 0x04;
    private static final byte MSG_ENABLE_NOTIFICATIONS = 0x05;
    private static final byte MSG_UNSOLICITED_NOTIFICATION = 0x06;
    private static final byte MSG_UNKNOWN_07 = 0x07;

    private static final int HEADER_LENGTH = 6;

    // Response codes
    private static final byte RESP_SUCCESS = 0x00;
    private static final byte RESP_UNKNOWN_MESSAGE = 0x01;
    private static final byte RESP_UNEXPECTED_ERROR = 0x02;
    private static final byte RESP_SERVICE_NOT_FOUND = 0x03;
    private static final byte RESP_CHAR_NOT_FOUND = 0x04;
    private static final byte RESP_CHAR_OP_NOT_SUPPORTED = 0x05;

    // Characteristic property flags (DIRCON uses different values from Android BLE)
    private static final byte PROP_READ = 0x01;
    private static final byte PROP_WRITE = 0x02;
    private static final byte PROP_NOTIFY = 0x04;
    private static final byte PROP_INDICATE = 0x08;

    // BLE Service UUIDs (16-bit)
    private static final int SVC_DEVICE_INFORMATION = 0x180A;
    private static final int SVC_FITNESS_MACHINE = 0x1826;
    private static final int SVC_RSC = 0x1814;
    private static final int SVC_HEART_RATE = 0x180D;

    // Device Information Service characteristics
    private static final int CHAR_MANUFACTURER_NAME = 0x2A29;
    private static final int CHAR_SERIAL_NUMBER = 0x2A25;
    private static final int CHAR_HARDWARE_REVISION = 0x2A27;
    private static final int CHAR_FIRMWARE_REVISION = 0x2A26;

    // Wahoo proprietary service UUID (full 128-bit, non-standard base)
    // A026EE0E-0A7D-4AB3-97FA-F1500F9FEB8B
    private static final byte[] SVC_WAHOO_EE01 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xEE, 0x01, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] SVC_WAHOO_PROPRIETARY = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xEE, 0x0E, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] SVC_WAHOO_EE06 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xEE, 0x06, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };

    // Wahoo proprietary characteristics (from KICKR RUN)
    // A026E03E - WRITE|NOTIFY, A026E03D - NOTIFY, A026E040 - NOTIFY
    private static final byte[] CHAR_WAHOO_E03E = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x3E, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E03D = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x3D, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E040 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x40, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E002 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x02, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E004 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x04, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E03B = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x3B, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E023 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x23, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };
    private static final byte[] CHAR_WAHOO_E018 = new byte[]{
            (byte) 0xA0, 0x26, (byte) 0xE0, 0x18, 0x0A, 0x7D, 0x4A, (byte) 0xB3,
            (byte) 0x97, (byte) 0xFA, (byte) 0xF1, 0x50, 0x0F, (byte) 0x9F, (byte) 0xEB, (byte) 0x8B
    };

    // Fitness Machine Service characteristics
    private static final int CHAR_FM_FEATURE = 0x2ACC;
    private static final int CHAR_FM_SUPPORTED_SPEED_RANGE = 0x2AD4;
    private static final int CHAR_FM_SUPPORTED_INCLINATION_RANGE = 0x2AD5;
    private static final int CHAR_FM_SUPPORTED_RESISTANCE_RANGE = 0x2AD6;
    private static final int CHAR_FM_CONTROL_POINT = 0x2AD9;
    private static final int CHAR_TREADMILL_DATA = 0x2ACD;
    private static final int CHAR_INDOOR_BIKE_DATA = 0x2AD2;
    private static final int CHAR_TRAINING_STATUS = 0x2AD3;
    private static final int CHAR_MACHINE_STATUS = 0x2ADA;

    // Running Speed & Cadence characteristics
    private static final int CHAR_RSC_FEATURE = 0x2A54;
    private static final int CHAR_SENSOR_LOCATION = 0x2A5D;
    private static final int CHAR_RSC_MEASUREMENT = 0x2A53;
    private static final int CHAR_SC_CONTROL_POINT = 0x2A55;

    // Heart Rate characteristics
    private static final int CHAR_HR_MEASUREMENT = 0x2A37;

    // Wahoo proprietary control point (placed under 0x1826 service)
    private static final int CHAR_WAHOO_CONTROL_POINT = 0xE005;

    // Wahoo proprietary opcodes
    private static final byte WAHOO_OP_SET_SLOPE = 0x11;      // KICKR RUN treadmill slope (int16 LE, ×100)
    private static final byte WAHOO_OP_SET_ERG_MODE = 0x42;   // Target power (watts)
    private static final byte WAHOO_OP_SET_SIM_MODE = 0x43;   // Physics config (weight/CRR/CW)
    private static final byte WAHOO_OP_SET_SIM_GRADE = 0x46;  // Incline grade (normalized float)

    // FTMS Control Point opcodes
    private static final byte OP_REQUEST_CONTROL = 0x00;
    private static final byte OP_RESET = 0x01;
    private static final byte OP_SET_TARGET_SPEED = 0x02;
    private static final byte OP_SET_TARGET_INCLINATION = 0x03;
    private static final byte OP_SET_TARGET_RESISTANCE = 0x04;
    private static final byte OP_START_OR_RESUME = 0x07;
    private static final byte OP_STOP_OR_PAUSE = 0x08;
    private static final byte OP_SET_INDOOR_BIKE_SIMULATION = 0x11;

    private final Context context;
    private final GrpcControlService grpc;
    private final FTMSService ftmsService;

    private ServerSocket serverSocket;
    private final Map<Socket, ClientState> clients = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    private ScheduledExecutorService notificationScheduler;
    private JmDNS jmdns;
    private WifiManager.MulticastLock multicastLock;

    // Wahoo simulation state (from setSimMode)
    private double simWeight = 75.0;
    private double simRollingResistance = 0.004;
    private double simWindResistance = 0.4;

    // Incline tracking for DIRCON clients (mirrors FTMSService behavior)
    private double lastNotifiedIncline = Double.NaN;
    private double ftmsTargetIncline = Double.NaN;
    private boolean controlGranted = false;
    private static final double INCLINE_TOLERANCE = 0.3;

    /**
     * Per-client state tracking for DIRCON connections.
     */
    private static class ClientState {
        final Socket socket;
        final OutputStream outputStream;
        int lastSeqNumber = -1;
        final Set<Integer> notifySubscriptions = new HashSet<>();
        final ArrayDeque<PendingNotification> pendingPostResponseNotifications = new ArrayDeque<>();
        boolean loggedFirstBroadcast = false;

        ClientState(Socket socket) throws IOException {
            this.socket = socket;
            this.outputStream = socket.getOutputStream();
        }
    }

    private static class PendingNotification {
        final byte[] uuidBytes;
        final byte[] data;

        PendingNotification(byte[] uuidBytes, byte[] data) {
            this.uuidBytes = uuidBytes;
            this.data = data;
        }
    }

    public DirconServer(Context context, GrpcControlService grpc, FTMSService ftmsService) {
        this.context = context;
        this.grpc = grpc;
        this.ftmsService = ftmsService;
    }

    /**
     * Start the DIRCON server: TCP listener, mDNS advertisement, and notification loop.
     */
    public void start() {
        if (running) return;
        running = true;

        // Start TCP server
        new Thread(this::runServer, "DIRCON-Server").start();

        // Start mDNS advertisement
        new Thread(this::startMdns, "DIRCON-mDNS").start();

        // Start periodic notification broadcast (every 500ms)
        notificationScheduler = Executors.newSingleThreadScheduledExecutor();
        notificationScheduler.scheduleAtFixedRate(
                this::broadcastNotifications, 1000, 500, TimeUnit.MILLISECONDS
        );

        Log.i(LOG_TAG, "DIRCON server started on port " + DIRCON_PORT);
    }

    /**
     * Stop the DIRCON server and clean up all resources.
     */
    public void stop() {
        running = false;

        if (notificationScheduler != null) {
            notificationScheduler.shutdown();
        }

        stopMdns();

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error closing server socket", e);
        }

        for (Socket s : clients.keySet()) {
            try { s.close(); } catch (IOException e) { /* ignore */ }
        }
        clients.clear();

        Log.i(LOG_TAG, "DIRCON server stopped");
    }

    // --- TCP Server ---

    private void runServer() {
        try {
            serverSocket = new ServerSocket(DIRCON_PORT);
            Log.i(LOG_TAG, "Listening on port " + DIRCON_PORT);

            while (running) {
                Socket socket = serverSocket.accept();
                Log.i(LOG_TAG, "Client connected: " + socket.getRemoteSocketAddress());

                try {
                    ClientState client = new ClientState(socket);
                    clients.put(socket, client);
                    Log.i(LOG_TAG, "=== NEW TCP CONNECTION from " + socket.getRemoteSocketAddress()
                            + " | Total clients: " + clients.size());
                    new Thread(() -> handleClient(socket, client), "DIRCON-Client").start();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Error setting up client", e);
                    try { socket.close(); } catch (IOException ex) { /* ignore */ }
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.e(LOG_TAG, "Server error", e);
            }
        }
    }

    private void handleClient(Socket socket, ClientState client) {
        try {
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

            while (running && !socket.isClosed()) {
                int bytesRead = in.read(buffer);
                if (bytesRead < 0) {
                    Log.i(LOG_TAG, ">>> TCP read returned -1 (EOF) from " + socket.getRemoteSocketAddress());
                    break;
                }

                // Log every raw TCP read
                byte[] rawChunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, rawChunk, 0, bytesRead);
                Log.i(LOG_TAG, ">>> TCP RAW IN (" + bytesRead + " bytes) from "
                        + socket.getRemoteSocketAddress() + ": " + bytesToHex(rawChunk));

                accumulator.write(buffer, 0, bytesRead);

                byte[] data = accumulator.toByteArray();
                int offset = 0;

                while (offset < data.length) {
                    // Check if we have enough for a header
                    if (data.length - offset < HEADER_LENGTH) {
                        Log.i(LOG_TAG, ">>> Waiting for more data, have " + (data.length - offset) + " bytes, need " + HEADER_LENGTH + " for header");
                        break;
                    }

                    // Parse payload length from header (big-endian)
                    int payloadLen = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
                    int totalLen = HEADER_LENGTH + payloadLen;

                    // Check if we have the complete packet
                    if (data.length - offset < totalLen) {
                        Log.i(LOG_TAG, ">>> Waiting for more data, have " + (data.length - offset) + " bytes, need " + totalLen + " for complete packet");
                        break;
                    }

                    // Parse the packet
                    byte msgType = data[offset + 1];
                    byte seqNum = data[offset + 2];
                    byte respCode = data[offset + 3];

                    // Log the parsed header
                    byte[] fullPacket = new byte[totalLen];
                    System.arraycopy(data, offset, fullPacket, 0, totalLen);
                    Log.i(LOG_TAG, ">>> DIRCON PKT: ver=0x" + String.format("%02X", data[offset])
                            + " msg=0x" + String.format("%02X", msgType)
                            + " seq=" + (seqNum & 0xFF)
                            + " rc=0x" + String.format("%02X", respCode)
                            + " payloadLen=" + payloadLen
                            + " raw=" + bytesToHex(fullPacket));

                    // Extract payload
                    byte[] payload = new byte[payloadLen];
                    if (payloadLen > 0) {
                        System.arraycopy(data, offset + HEADER_LENGTH, payload, 0, payloadLen);
                        Log.i(LOG_TAG, ">>> PAYLOAD: " + bytesToHex(payload));
                    }

                    // Determine if this is a request
                    int seq = seqNum & 0xFF;
                    boolean isRequest = (respCode == 0x00) &&
                            (client.lastSeqNumber < 0 || client.lastSeqNumber != seq);

                    Log.i(LOG_TAG, ">>> isRequest=" + isRequest
                            + " (respCode=0x" + String.format("%02X", respCode)
                            + " lastSeq=" + client.lastSeqNumber
                            + " thisSeq=" + seq + ")");

                    if (isRequest) {
                        client.lastSeqNumber = seq;
                        byte[] response = processRequest(msgType, seq, payload, client);
                        if (response != null) {
                            Log.i(LOG_TAG, "<<< DIRCON RESP (" + response.length + " bytes): " + bytesToHex(response));
                            synchronized (client.outputStream) {
                                client.outputStream.write(response);
                                flushPostResponseNotificationsLocked(client);
                                client.outputStream.flush();
                            }
                        } else {
                            Log.w(LOG_TAG, "<<< processRequest returned null for msg=0x" + String.format("%02X", msgType));
                        }
                    } else {
                        Log.i(LOG_TAG, ">>> SKIPPED (not a request)");
                    }

                    offset += totalLen;
                }

                // Keep unconsumed bytes
                if (offset > 0) {
                    byte[] remaining = new byte[data.length - offset];
                    if (remaining.length > 0) {
                        System.arraycopy(data, offset, remaining, 0, remaining.length);
                    }
                    accumulator.reset();
                    if (remaining.length > 0) {
                        accumulator.write(remaining);
                    }
                }
            }
        } catch (IOException e) {
            Log.i(LOG_TAG, "Client disconnected: " + socket.getRemoteSocketAddress());
        } finally {
            clients.remove(socket);
            try { socket.close(); } catch (IOException e) { /* ignore */ }
            Log.i(LOG_TAG, "Client removed. Active clients: " + clients.size());
        }
    }

    // --- Request Processing ---

    private byte[] processRequest(byte msgType, int seqNum, byte[] payload, ClientState client) {
        String msgName;
        switch (msgType) {
            case MSG_DISCOVER_SERVICES: msgName = "DISC_SVC"; break;
            case MSG_DISCOVER_CHARACTERISTICS: msgName = "DISC_CHAR"; break;
            case MSG_READ_CHARACTERISTIC: msgName = "READ"; break;
            case MSG_WRITE_CHARACTERISTIC: msgName = "WRITE"; break;
            case MSG_ENABLE_NOTIFICATIONS: msgName = "EN_NOTIF"; break;
            case MSG_UNSOLICITED_NOTIFICATION: msgName = "NOTIF"; break;
            case MSG_UNKNOWN_07: msgName = "UNK_07"; break;
            default: msgName = "UNK_0x" + String.format("%02X", msgType); break;
        }
        Log.i(LOG_TAG, "=== processRequest: " + msgName + " seq=" + seqNum
                + " payloadLen=" + payload.length);

        try {
            switch (msgType) {
                case MSG_DISCOVER_SERVICES:
                    return handleDiscoverServices(seqNum);
                case MSG_DISCOVER_CHARACTERISTICS:
                    return handleDiscoverCharacteristics(seqNum, payload);
                case MSG_READ_CHARACTERISTIC:
                    return handleReadCharacteristic(seqNum, payload);
                case MSG_WRITE_CHARACTERISTIC:
                    return handleWriteCharacteristic(seqNum, payload, client);
                case MSG_ENABLE_NOTIFICATIONS:
                    return handleEnableNotifications(seqNum, payload, client);
                case MSG_UNKNOWN_07:
                    Log.i(LOG_TAG, "=== Handling unknown 0x07, responding SUCCESS");
                    return buildResponse(MSG_UNKNOWN_07, seqNum, RESP_SUCCESS, new byte[0]);
                default:
                    Log.w(LOG_TAG, "=== UNHANDLED message type: 0x" + String.format("%02X", msgType)
                            + " payload: " + bytesToHex(payload));
                    return buildResponse(msgType, seqNum, RESP_UNKNOWN_MESSAGE, new byte[0]);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "=== ERROR processing " + msgName, e);
            return buildResponse(msgType, seqNum, RESP_UNEXPECTED_ERROR, new byte[0]);
        }
    }

    private byte[] handleDiscoverServices(int seqNum) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            payload.write(uuidToBytes(SVC_DEVICE_INFORMATION));
            payload.write(uuidToBytes(SVC_FITNESS_MACHINE));
            if (isTreadmillDirconProfile()) {
                // Match the real KICKR RUN treadmill service order exactly:
                // 180A, EE01, EE0E, 1826, 1814, EE06
                payload = new ByteArrayOutputStream();
                payload.write(uuidToBytes(SVC_DEVICE_INFORMATION));
                payload.write(SVC_WAHOO_EE01);
                payload.write(SVC_WAHOO_PROPRIETARY);
                payload.write(uuidToBytes(SVC_FITNESS_MACHINE));
                payload.write(uuidToBytes(SVC_RSC));
                payload.write(SVC_WAHOO_EE06);
            }
        } catch (IOException e) { /* ByteArrayOutputStream won't throw */ }

        int numServices = payload.size() / 16;
        Log.i(LOG_TAG, "Discover Services: returning " + numServices + " services");
        return buildResponse(MSG_DISCOVER_SERVICES, seqNum, RESP_SUCCESS, payload.toByteArray());
    }

    private byte[] handleDiscoverCharacteristics(int seqNum, byte[] payload) {
        if (payload.length < 16) {
            return buildResponse(MSG_DISCOVER_CHARACTERISTICS, seqNum, RESP_SERVICE_NOT_FOUND, new byte[0]);
        }

        int serviceUuid = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        // Check if this is any Wahoo proprietary service (A026xxxx base)
        boolean isWahooBase = payload.length >= 16 &&
                (payload[0] & 0xFF) == 0xA0 && (payload[1] & 0xFF) == 0x26 &&
                (payload[4] & 0xFF) == 0x0A && (payload[5] & 0xFF) == 0x7D;
        ByteArrayOutputStream resp = new ByteArrayOutputStream();

        try {
            // Echo the service UUID exactly as received
            resp.write(payload, 0, 16);

            if (serviceUuid == SVC_DEVICE_INFORMATION) {
                writeCharEntry(resp, CHAR_MANUFACTURER_NAME, PROP_READ);
                writeCharEntry(resp, CHAR_SERIAL_NUMBER, PROP_READ);
                writeCharEntry(resp, CHAR_HARDWARE_REVISION, PROP_READ);
                writeCharEntry(resp, CHAR_FIRMWARE_REVISION, PROP_READ);
                Log.i(LOG_TAG, "Discover Characteristics for DevInfo: 4 characteristics");
            } else if (isTreadmillDirconProfile() && isWahooBase && serviceUuid == 0xEE01) {
                writeWahooCharEntry(resp, CHAR_WAHOO_E002, PROP_WRITE | PROP_NOTIFY);
                writeWahooCharEntry(resp, CHAR_WAHOO_E004, PROP_NOTIFY);
                writeWahooCharEntry(resp, CHAR_WAHOO_E03B, PROP_WRITE | PROP_NOTIFY);
                Log.i(LOG_TAG, "Discover Characteristics for Wahoo 0xEE01: 3 characteristics");
            } else if (isTreadmillDirconProfile() && isWahooBase && serviceUuid == 0xEE0E) {
                // Wahoo proprietary service 0xEE0E — expose same chars as KICKR RUN
                writeWahooCharEntry(resp, CHAR_WAHOO_E03E, PROP_WRITE | PROP_NOTIFY);
                writeWahooCharEntry(resp, CHAR_WAHOO_E03D, PROP_NOTIFY);
                writeWahooCharEntry(resp, CHAR_WAHOO_E040, PROP_NOTIFY);
                Log.i(LOG_TAG, "Discover Characteristics for Wahoo 0xEE0E: 3 characteristics");
            } else if (isTreadmillDirconProfile() && isWahooBase && serviceUuid == 0xEE06) {
                writeWahooCharEntry(resp, CHAR_WAHOO_E023, PROP_WRITE | PROP_NOTIFY);
                writeWahooCharEntry(resp, CHAR_WAHOO_E018, PROP_WRITE | PROP_NOTIFY);
                Log.i(LOG_TAG, "Discover Characteristics for Wahoo 0xEE06: 2 characteristics");
            } else if (serviceUuid == SVC_FITNESS_MACHINE) {
                if (isTreadmillDirconProfile()) {
                    writeCharEntry(resp, CHAR_FM_FEATURE, PROP_READ);
                    writeCharEntry(resp, CHAR_FM_SUPPORTED_INCLINATION_RANGE, PROP_READ);
                    writeCharEntry(resp, CHAR_FM_CONTROL_POINT, PROP_WRITE | PROP_NOTIFY);
                    writeCharEntry(resp, CHAR_TREADMILL_DATA, PROP_NOTIFY);
                    writeCharEntry(resp, CHAR_MACHINE_STATUS, PROP_NOTIFY);
                    Log.i(LOG_TAG, "Discover Characteristics for FTMS treadmill profile: 5 characteristics");
                } else {
                    writeCharEntry(resp, CHAR_FM_FEATURE, PROP_READ);
                    writeCharEntry(resp, CHAR_FM_SUPPORTED_SPEED_RANGE, PROP_READ);
                    writeCharEntry(resp, CHAR_FM_SUPPORTED_RESISTANCE_RANGE, PROP_READ);
                    writeCharEntry(resp, CHAR_FM_CONTROL_POINT, PROP_WRITE | PROP_NOTIFY);
                    writeCharEntry(resp, CHAR_INDOOR_BIKE_DATA, PROP_NOTIFY);
                    writeCharEntry(resp, CHAR_MACHINE_STATUS, PROP_NOTIFY);
                    Log.i(LOG_TAG, "Discover Characteristics for FTMS generic profile: 6 characteristics");
                }
            } else if (isTreadmillDirconProfile() && serviceUuid == SVC_RSC) {
                writeCharEntry(resp, CHAR_RSC_MEASUREMENT, PROP_NOTIFY);
                writeCharEntry(resp, CHAR_RSC_FEATURE, PROP_READ);
                Log.i(LOG_TAG, "Discover Characteristics for RSC: 2 characteristics");
            } else if (serviceUuid == SVC_HEART_RATE) {
                writeCharEntry(resp, CHAR_HR_MEASUREMENT, PROP_NOTIFY);
                Log.i(LOG_TAG, "Discover Characteristics for HR: 1 characteristic");
            } else {
                Log.w(LOG_TAG, "Service not found: 0x" + String.format("%04X", serviceUuid));
                return buildResponse(MSG_DISCOVER_CHARACTERISTICS, seqNum, RESP_SERVICE_NOT_FOUND, new byte[0]);
            }
        } catch (IOException e) { /* ByteArrayOutputStream won't throw */ }

        return buildResponse(MSG_DISCOVER_CHARACTERISTICS, seqNum, RESP_SUCCESS, resp.toByteArray());
    }

    private byte[] handleReadCharacteristic(int seqNum, byte[] payload) {
        if (payload.length < 16) {
            return buildResponse(MSG_READ_CHARACTERISTIC, seqNum, RESP_CHAR_NOT_FOUND, new byte[0]);
        }

        // Extract the raw 16-byte UUID for echoing back
        byte[] rawUuid = new byte[16];
        System.arraycopy(payload, 0, rawUuid, 0, 16);

        int charUuid = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        byte[] data;

        // Check for Wahoo proprietary characteristics (match on short UUID to handle any base template)
        if (charUuid == 0xE002 || charUuid == 0xE004 || charUuid == 0xE018
                || charUuid == 0xE023 || charUuid == 0xE03B
                || charUuid == 0xE03E || charUuid == 0xE03D || charUuid == 0xE040) {
            // Return empty data for proprietary reads
            data = new byte[0];
        } else {
            switch (charUuid) {
                // Device Information Service (0x180A)
                case CHAR_MANUFACTURER_NAME: // 0x2A29
                    data = isTreadmillDirconProfile()
                            ? "Wahoo Fitness".getBytes()
                            : GENERIC_MANUFACTURER_NAME.getBytes();
                    break;
                case CHAR_SERIAL_NUMBER: // 0x2A25
                    data = getDirconSerialNumber().getBytes();
                    break;
                case CHAR_HARDWARE_REVISION: // 0x2A27
                    data = isTreadmillDirconProfile()
                            ? KICKR_RUN_HARDWARE_REV.getBytes()
                            : GENERIC_HARDWARE_REV.getBytes();
                    break;
                case CHAR_FIRMWARE_REVISION: // 0x2A26
                    data = isTreadmillDirconProfile()
                            ? KICKR_RUN_FIRMWARE_REV.getBytes()
                            : BuildConfig.VERSION_NAME.getBytes();
                    break;

                // Fitness Machine Service (0x1826)
                case CHAR_FM_FEATURE: // 0x2ACC
                    data = buildFeatureValue();
                    break;
                case CHAR_FM_SUPPORTED_INCLINATION_RANGE: // 0x2AD5
                    data = buildSupportedInclinationRange();
                    break;

                case CHAR_FM_SUPPORTED_SPEED_RANGE: // 0x2AD4
                    data = buildSupportedSpeedRange();
                    break;
                case CHAR_FM_SUPPORTED_RESISTANCE_RANGE: // 0x2AD6
                    data = buildSupportedResistanceRange();
                    break;
                case CHAR_TRAINING_STATUS: // 0x2AD3
                    data = new byte[]{0x00, 0x01}; // Idle
                    break;

                // Running Speed & Cadence (0x1814)
                case CHAR_RSC_FEATURE: // 0x2A54
                    data = new byte[]{0x02, 0x00}; // Total Distance supported
                    break;
                case CHAR_SENSOR_LOCATION: // 0x2A5D
                    data = new byte[]{0x01}; // Top of shoe
                    break;
                default:
                    Log.w(LOG_TAG, "Read: characteristic not found 0x" + String.format("%04X", charUuid));
                    return buildResponse(MSG_READ_CHARACTERISTIC, seqNum, RESP_CHAR_NOT_FOUND, new byte[0]);
            }
        }

        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        try {
            resp.write(rawUuid);
            resp.write(data);
        } catch (IOException e) { /* ignore */ }

        Log.i(LOG_TAG, "=== READ 0x" + String.format("%04X", charUuid) + " → " + bytesToHex(data));
        return buildResponse(MSG_READ_CHARACTERISTIC, seqNum, RESP_SUCCESS, resp.toByteArray());
    }

    private byte[] handleWriteCharacteristic(int seqNum, byte[] payload, ClientState client) {
        if (payload.length < 16) {
            return buildResponse(MSG_WRITE_CHARACTERISTIC, seqNum, RESP_CHAR_NOT_FOUND, new byte[0]);
        }

        // Extract the raw 16-byte UUID for echoing back
        byte[] rawUuid = new byte[16];
        System.arraycopy(payload, 0, rawUuid, 0, 16);

        int charUuid = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);

        // Extract the write data (everything after the 16-byte UUID)
        byte[] writeData = new byte[0];
        if (payload.length > 16) {
            writeData = new byte[payload.length - 16];
            System.arraycopy(payload, 16, writeData, 0, writeData.length);
        }

        Log.i(LOG_TAG, "=== WRITE to 0x" + String.format("%04X", charUuid)
                + " rawUUID=" + bytesToHex(rawUuid)
                + " data=" + bytesToHex(writeData));

        byte[] responseData = null;

        if (charUuid == CHAR_FM_CONTROL_POINT) {
            if (writeData.length > 0) {
                byte opcode = writeData[0];
                byte[] ftmsResponse = processFtmsCommand(opcode, writeData);
                queuePostResponseNotification(client, CHAR_FM_CONTROL_POINT, ftmsResponse);

                Log.i(LOG_TAG, "FTMS Control Point opcode: 0x" + String.format("%02X", opcode));
            }
        } else if (charUuid == CHAR_WAHOO_CONTROL_POINT) {
            if (writeData.length > 0) {
                responseData = processWahooControlPoint(writeData);
                Log.i(LOG_TAG, "Wahoo E005 opcode: 0x" + String.format("%02X", writeData[0])
                        + " response: " + bytesToHex(responseData));
            }
        } else if (charUuid == 0xE03E) {
            // Keep E03E support for the KICKR RUN-style proprietary path.
            if (writeData.length > 0) {
                Log.i(LOG_TAG, "Wahoo E03E write: " + bytesToHex(writeData));
                byte[] wahooResponse = processLegacyWahooControlPoint(writeData);
                // Send response as notification on E03E (format: FE + opcode + status)
                sendWahooNotification(client, CHAR_WAHOO_E03E, 0xE03E, wahooResponse);
                Log.i(LOG_TAG, "Wahoo E03E opcode: 0x" + String.format("%02X", writeData[0])
                        + " response: " + bytesToHex(wahooResponse));
            }
        } else {
            Log.i(LOG_TAG, "Write to unhandled characteristic 0x" + String.format("%04X", charUuid)
                    + " data: " + bytesToHex(writeData));
        }

        // Build write acknowledgment (echo the raw UUID + optional response data)
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        try {
            resp.write(rawUuid);
            if (responseData != null) {
                resp.write(responseData);
            }
        } catch (IOException e) { /* ignore */ }

        return buildResponse(MSG_WRITE_CHARACTERISTIC, seqNum, RESP_SUCCESS, resp.toByteArray());
    }

    private byte[] handleEnableNotifications(int seqNum, byte[] payload, ClientState client) {
        if (payload.length < 17) {
            return buildResponse(MSG_ENABLE_NOTIFICATIONS, seqNum, RESP_CHAR_NOT_FOUND, new byte[0]);
        }

        // Extract raw UUID for echo
        byte[] rawUuid = new byte[16];
        System.arraycopy(payload, 0, rawUuid, 0, 16);

        int charUuid = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        boolean enable = payload[16] != 0;

        if (enable) {
            client.notifySubscriptions.add(charUuid);
            Log.i(LOG_TAG, "Notifications ENABLED for 0x" + String.format("%04X", charUuid));
        } else {
            client.notifySubscriptions.remove(charUuid);
            Log.i(LOG_TAG, "Notifications DISABLED for 0x" + String.format("%04X", charUuid));
        }

        // Response: echo the raw UUID (no enable flag)
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        try {
            resp.write(rawUuid);
        } catch (IOException e) { /* ignore */ }

        return buildResponse(MSG_ENABLE_NOTIFICATIONS, seqNum, RESP_SUCCESS, resp.toByteArray());
    }

    // --- FTMS Control Point Processing ---

    private byte[] processFtmsCommand(byte opcode, byte[] data) {
        switch (opcode) {
            case OP_REQUEST_CONTROL:
                controlGranted = true;
                Log.i(LOG_TAG, "Control granted (DIRCON)");
                return new byte[]{(byte) 0x80, opcode, 0x01};

            case OP_RESET:
                controlGranted = false;
                Log.i(LOG_TAG, "Reset (DIRCON)");
                return new byte[]{(byte) 0x80, opcode, 0x01};

            case OP_SET_TARGET_SPEED:
                if (!controlGranted) {
                    return new byte[]{(byte) 0x80, opcode, 0x05};
                }
                if (data.length >= 3) {
                    int speedRaw = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
                    double speedKmh = speedRaw / 100.0;
                    Log.i(LOG_TAG, "Set speed: " + speedKmh + " km/h (DIRCON)");
                    if (grpc != null) grpc.setSpeed(speedKmh);
                    return new byte[]{(byte) 0x80, opcode, 0x01};
                }
                return new byte[]{(byte) 0x80, opcode, 0x02};

            case OP_SET_TARGET_INCLINATION:
                if (!controlGranted) {
                    return new byte[]{(byte) 0x80, opcode, 0x05};
                }
                if (data.length >= 3) {
                    int inclRaw = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
                    if (inclRaw > 32767) inclRaw -= 65536; // sign extend
                    double requestedInclination = inclRaw / 10.0;
                    double inclination = normalizeInclinePercent(requestedInclination);
                    Log.i(LOG_TAG, String.format(
                            "Set incline (DIRCON FTMS): requested=%.1f%% applied=%.1f%%",
                            requestedInclination, inclination));
                    ftmsTargetIncline = inclination;
                    // Also notify FTMSService so BLE clients don't see this as manual
                    ftmsService.setFtmsTargetIncline(inclination);
                    if (grpc != null) grpc.setIncline(inclination);
                    return new byte[]{(byte) 0x80, opcode, 0x01};
                }
                return new byte[]{(byte) 0x80, opcode, 0x02};

            case OP_SET_TARGET_RESISTANCE:
                if (!controlGranted) {
                    return new byte[]{(byte) 0x80, opcode, 0x05};
                }
                if (data.length >= 2) {
                    int resRaw = data[1] & 0xFF;
                    double resistance = resRaw / 10.0;
                    Log.i(LOG_TAG, "Set resistance: " + resistance + " (DIRCON)");
                    if (grpc != null) grpc.setResistance(resistance);
                    return new byte[]{(byte) 0x80, opcode, 0x01};
                }
                return new byte[]{(byte) 0x80, opcode, 0x02};

            case OP_SET_INDOOR_BIKE_SIMULATION:
                // Acknowledge but no specific hardware action
                Log.i(LOG_TAG, "Indoor bike simulation params received (DIRCON)");
                return new byte[]{(byte) 0x80, opcode, 0x01};

            default:
                Log.w(LOG_TAG, "Unsupported FTMS opcode: 0x" + String.format("%02X", opcode));
                return new byte[]{(byte) 0x80, opcode, 0x02};
        }
    }

    // --- Wahoo Proprietary Control Point (0xE005) ---

    /**
     * Process a write to the Wahoo proprietary control point (0xE005).
     * This is how Zwift sends incline commands to treadmills over DIRCON.
     */
    private byte[] processWahooControlPoint(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[]{(byte) 0x80, 0x00, 0x02};
        }

        byte opcode = data[0];
        boolean handled = applyWahooControlCommand(data);

        // E005 uses the FTMS-style response in the write acknowledgment.
        return new byte[]{(byte) 0x80, opcode, handled ? (byte) 0x01 : (byte) 0x02};
    }

    private byte[] processLegacyWahooControlPoint(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[]{(byte) 0xFE, 0x00, 0x02};
        }

        byte opcode = data[0];
        applyWahooControlCommand(data);

        // E03E preserves the legacy KICKR RUN-style response shape.
        return new byte[]{(byte) 0xFE, opcode, 0x02};
    }

    private boolean applyWahooControlCommand(byte[] data) {
        byte opcode = data[0];

        switch (opcode) {
            case WAHOO_OP_SET_SLOPE: // 0x11 — legacy treadmill incline encoding
                handleSetSlope(data);
                return true;

            case WAHOO_OP_SET_SIM_GRADE: // 0x46 — Zwift treadmill grade encoding
                handleSetSimGrade(data);
                return true;

            case WAHOO_OP_SET_SIM_MODE: // 0x43 — Physics config (weight/CRR/CW)
                handleSetSimMode(data);
                return true;

            case WAHOO_OP_SET_ERG_MODE: // 0x42 — Target power (not used for treadmill)
                handleSetErgMode(data);
                return true;

            default:
                Log.w(LOG_TAG, "Unknown Wahoo opcode: 0x" + String.format("%02X", opcode)
                        + " data: " + bytesToHex(data));
                return false;
        }
    }

    /**
     * Decode Wahoo setSlope (0x11) and apply incline to the treadmill.
     * This is the primary opcode Zwift uses for treadmill incline control.
     *
     * Format: [0x11] [slope_int16_LE] where slope = incline_percent × 100
     * Example: 5.0% incline → slope = 500 → bytes: 0x11, 0xF4, 0x01
     *
     * The prompt docs originally described this as ×10, but live Zwift/KICKR RUN
     * traffic and qdomyos's treadmill path both use hundredths of a percent here.
     */
    private void handleSetSlope(byte[] data) {
        if (data.length < 3) {
            Log.w(LOG_TAG, "setSlope: insufficient data");
            return;
        }

        // Decode slope from int16 LE (bytes 1-2), value = incline × 100
        int slopeRaw = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        if (slopeRaw > 32767) slopeRaw -= 65536; // sign extend
        double requestedInclinePercent = slopeRaw / 100.0;
        double inclinePercent = normalizeInclinePercent(requestedInclinePercent);

        Log.i(LOG_TAG, String.format(
                "setSlope (0x11): raw=%d, requested=%.2f%% applied=%.1f%%",
                slopeRaw, requestedInclinePercent, inclinePercent));

        // Set FTMS target so manual incline detection doesn't fire
        ftmsTargetIncline = inclinePercent;
        ftmsService.setFtmsTargetIncline(inclinePercent);

        // Apply to treadmill via gRPC
        if (grpc != null) {
            grpc.setIncline(inclinePercent);
        }
    }

    /**
     * Decode Wahoo setSimGrade (0x46) and apply incline to the treadmill.
     *
     * Encoding: grade_encoded = (grade_float + 1.0) * 65535 / 2.0
     *   where grade_float is -1.0 to +1.0 (-100% to +100%)
     *
     * Decoding: grade_float = ((grade_encoded / 65535.0) * 2.0) - 1.0
     *           incline_percent = grade_float * 100.0
     */
    private void handleSetSimGrade(byte[] data) {
        if (data.length < 3) {
            Log.w(LOG_TAG, "setSimGrade: insufficient data");
            return;
        }

        // Decode grade from uint16 LE (bytes 1-2)
        int gradeEncoded = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);

        // Convert to incline percentage
        double gradeFloat = (((double) gradeEncoded / 65535.0) * 2.0) - 1.0;
        double requestedInclinePercent = gradeFloat * 100.0;
        double inclinePercent = normalizeInclinePercent(requestedInclinePercent);

        Log.i(LOG_TAG, String.format(
                "setSimGrade: encoded=%d, grade=%.4f, requested=%.2f%% applied=%.1f%%",
                gradeEncoded, gradeFloat, requestedInclinePercent, inclinePercent));

        // Set FTMS target so manual incline detection doesn't fire
        ftmsTargetIncline = inclinePercent;
        ftmsService.setFtmsTargetIncline(inclinePercent);

        // Apply to treadmill via gRPC
        if (grpc != null) {
            grpc.setIncline(inclinePercent);
        }
    }

    /**
     * Decode Wahoo setSimMode (0x43). Stores physics parameters.
     * Zwift sends this before setSimGrade to configure the simulation model.
     */
    private void handleSetSimMode(byte[] data) {
        if (data.length < 7) {
            Log.w(LOG_TAG, "setSimMode: insufficient data");
            return;
        }

        int weightEncoded = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        int rrcEncoded = (data[3] & 0xFF) | ((data[4] & 0xFF) << 8);
        int wrcEncoded = (data[5] & 0xFF) | ((data[6] & 0xFF) << 8);

        simWeight = weightEncoded / 100.0;
        simRollingResistance = rrcEncoded / 1000.0;
        simWindResistance = wrcEncoded / 1000.0;

        Log.i(LOG_TAG, String.format(
                "setSimMode: weight=%.1fkg, CRR=%.4f, CW=%.3f",
                simWeight, simRollingResistance, simWindResistance));
    }

    /**
     * Decode Wahoo setErgMode (0x42). Target power — not used for treadmills.
     */
    private void handleSetErgMode(byte[] data) {
        if (data.length < 3) return;

        int watts = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        Log.i(LOG_TAG, "setErgMode: " + watts + "W (ignored for treadmill)");
    }

    // --- Notification Broadcasting ---

    private void broadcastNotifications() {
        if (clients.isEmpty() || grpc == null) return;

        try {
            // Check for manual incline changes
            checkForManualInclineChange();

            byte[] indoorBikeData = buildIndoorBikeData();
            byte[] treadmillData = buildTreadmillData();
            byte[] rscData = buildRscData();
            byte[] wahooStatus = buildWahooStatus();
            byte[] wahooStatus2 = new byte[]{0x00, 0x00, 0x00, 0x00};
            boolean treadmillProfile = isTreadmillDirconProfile();

            for (ClientState client : clients.values()) {
                // Log subscribed characteristics on first broadcast to this client
                if (!client.loggedFirstBroadcast) {
                    client.loggedFirstBroadcast = true;
                    Log.i(LOG_TAG, "First broadcast to client. Subscriptions: " + client.notifySubscriptions);
                }

                if (treadmillProfile) {
                    // Send Wahoo E03D status first for the KICKR RUN treadmill profile.
                    sendWahooNotification(client, CHAR_WAHOO_E03D, 0xE03D, wahooStatus);
                    sendWahooNotification(client, CHAR_WAHOO_E03D, 0xE03D, wahooStatus2);
                    sendNotification(client, CHAR_TREADMILL_DATA, treadmillData);
                    sendNotification(client, CHAR_RSC_MEASUREMENT, rscData);
                } else {
                    sendNotification(client, CHAR_INDOOR_BIKE_DATA, indoorBikeData);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error broadcasting notifications", e);
        }
    }

    /**
     * Send a notification using a full 16-byte Wahoo proprietary UUID.
     * Uses the int UUID16 (e.g., 0xE03D) for subscription tracking.
     */
    private void sendWahooNotification(ClientState client, byte[] wahooUuidBytes, int uuid16, byte[] data) {
        if (!client.notifySubscriptions.contains(uuid16)) return;

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            payload.write(wahooUuidBytes);
            payload.write(data);
        } catch (IOException e) { return; }

        byte[] packet = buildPacket(MSG_UNSOLICITED_NOTIFICATION, 0, RESP_SUCCESS, payload.toByteArray());

        try {
            synchronized (client.outputStream) {
                client.outputStream.write(packet);
                client.outputStream.flush();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to send Wahoo notification, client likely disconnected");
        }
    }

    /**
     * Build Wahoo proprietary device status (0xE03D).
     * Mimics the KICKR RUN's 20-byte status notification.
     * Byte 0: 0xFF = device status flags
     * Byte 1: 0x01 = device ready/controllable
     */
    private byte[] buildWahooStatus() {
        return new byte[]{
                (byte) 0xFF, 0x01, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xD9, 0x53, (byte) 0xE4, 0x00,
                0x00, 0x00, 0x00, 0x00,
                (byte) 0xEE, (byte) 0xEF,
                0x00, 0x00, 0x00, 0x00
        };
    }

    private void sendNotification(ClientState client, int charUuid, byte[] data) {
        if (!client.notifySubscriptions.contains(charUuid)) return;

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            payload.write(uuidToBytes(charUuid));
            payload.write(data);
        } catch (IOException e) { return; }

        byte[] packet = buildPacket(MSG_UNSOLICITED_NOTIFICATION, 0, RESP_SUCCESS, payload.toByteArray());

        try {
            synchronized (client.outputStream) {
                client.outputStream.write(packet);
                client.outputStream.flush();
            }
        } catch (IOException e) {
            // Client disconnected — will be cleaned up by reader thread
            Log.e(LOG_TAG, "Failed to send notification for 0x" + String.format("%04X", charUuid));
        }
    }

    /**
     * Send a Machine Status notification to all DIRCON clients subscribed to 0x2ADA.
     * Called from FTMSService when a manual incline change is detected.
     */
    public void sendMachineStatusToAll(byte[] statusData) {
        for (ClientState client : clients.values()) {
            sendNotification(client, CHAR_MACHINE_STATUS, statusData);
        }
    }

    // --- Manual Incline Detection (mirrors FTMSService logic) ---

    private void checkForManualInclineChange() {
        if (grpc == null) return;
        double currentIncline = grpc.getLastInclinePercent();

        if (Double.isNaN(lastNotifiedIncline)) {
            lastNotifiedIncline = currentIncline;
            return;
        }

        if (currentIncline == lastNotifiedIncline) return;

        double previousIncline = lastNotifiedIncline;
        lastNotifiedIncline = currentIncline;

        // If FTMS has a target and current incline is moving toward it, skip
        if (!Double.isNaN(ftmsTargetIncline)) {
            double prevDistance = Math.abs(previousIncline - ftmsTargetIncline);
            double currDistance = Math.abs(currentIncline - ftmsTargetIncline);

            if (currDistance < prevDistance || currDistance <= INCLINE_TOLERANCE) {
                if (currDistance <= INCLINE_TOLERANCE) {
                    ftmsTargetIncline = Double.NaN;
                }
                return;
            }
            ftmsTargetIncline = Double.NaN;
        }

        // Manual change detected — send Machine Status to DIRCON clients
        int inclRaw = (int) (currentIncline * 10);
        byte[] status = new byte[3];
        status[0] = 0x06; // Target Inclination Changed
        status[1] = (byte) (inclRaw & 0xFF);
        status[2] = (byte) ((inclRaw >> 8) & 0xFF);

        Log.i(LOG_TAG, "Manual incline change: " + currentIncline + "% — sending Machine Status");

        for (ClientState client : clients.values()) {
            sendNotification(client, CHAR_MACHINE_STATUS, status);
        }
    }

    /**
     * Called by FTMSService when an incline command comes from BLE FTMS,
     * so DIRCON knows not to treat the resulting change as manual.
     */
    public void setFtmsTargetIncline(double incline) {
        this.ftmsTargetIncline = incline;
    }

    // --- Data Builders ---

    private byte[] buildFeatureValue() {
        byte[] value = new byte[8];

        if (!isTreadmillDirconProfile()) {
            // Cadence + Speed + Power features
            value[0] = (byte) 0x83;
            value[1] = 0x14;
            value[4] = 0x0C;
            value[5] = (byte) 0xE0;
        } else {
            // Align the treadmill profile with the documented DIRCON FTMS payload.
            // Mirror the real KICKR RUN treadmill feature bitmap.
            value[0] = 0x0C;
            value[1] = 0x00;
            value[2] = 0x00;
            value[3] = 0x00;
            value[4] = 0x02;
            value[5] = 0x00;
            value[6] = 0x00;
            value[7] = 0x00;
        }
        return value;
    }

    private byte[] buildSupportedInclinationRange() {
        // int16 LE min, int16 LE max, uint16 LE step (resolution 0.1%)
        byte[] data = new byte[6];
        // Match the KICKR RUN's reported treadmill range exactly.
        int minIncline = -30; // -3.0%
        int maxIncline = 150; // 15.0%
        writeInt16LE(data, 0, minIncline);
        writeInt16LE(data, 2, maxIncline);
        writeUint16LE(data, 4, 1); // step: 0.1% (matching KICKR RUN)
        return data;
    }

    private byte[] buildSupportedSpeedRange() {
        // uint16 LE min (km/h*100), uint16 LE max (km/h*100), uint16 LE step (km/h*100)
        byte[] data = new byte[6];
        writeUint16LE(data, 0, (int) (getMinSpeedKph() * 100));
        writeUint16LE(data, 2, (int) (getMaxSpeedKph() * 100));
        writeUint16LE(data, 4, 10);     // step: 0.1 km/h
        return data;
    }

    private byte[] buildSupportedResistanceRange() {
        byte[] data = new byte[6];
        writeUint16LE(data, 0, (int) (getMinResistance() * 10));
        writeUint16LE(data, 2, (int) (getMaxResistance() * 10));
        writeUint16LE(data, 4, 10);     // step: 1.0
        return data;
    }

    private double getMinSpeedKph() {
        return grpc != null ? grpc.getMinSpeedKph() : 0.5;
    }

    private double getMaxSpeedKph() {
        return grpc != null ? grpc.getMaxSpeedKph() : 22.0;
    }

    private double getMinInclinePercent() {
        return grpc != null ? grpc.getMinInclinePercent() : -6.0;
    }

    private double getMaxInclinePercent() {
        return grpc != null ? grpc.getMaxInclinePercent() : 40.0;
    }

    private double getMinResistance() {
        return grpc != null ? grpc.getMinResistance() : 0.0;
    }

    private double getMaxResistance() {
        return grpc != null ? grpc.getMaxResistance() : 30.0;
    }

    private byte[] buildTreadmillData() {
        // Mirror the compact KICKR RUN treadmill payload:
        // instantaneous speed + total distance + incline/ramp.
        byte[] data = new byte[11];

        // Flags: total distance + inclination/ramp angle present
        data[0] = 0x0C;
        data[1] = 0x00;

        // Instantaneous Speed (uint16 LE, 0.01 km/h)
        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) speed = 0;
        writeUint16LE(data, 2, speed);

        // Total Distance (uint24 LE, meters)
        int distMeters = (int) (grpc.getLastDistanceKm() * 1000);
        if (distMeters < 0) distMeters = 0;
        data[4] = (byte) (distMeters & 0xFF);
        data[5] = (byte) ((distMeters >> 8) & 0xFF);
        data[6] = (byte) ((distMeters >> 16) & 0xFF);

        // Inclination (int16 LE, 0.1%)
        int inclination = (int) (grpc.getLastInclinePercent() * 10);
        writeInt16LE(data, 7, inclination);

        // The real KICKR RUN reports ramp angle as unavailable over DIRCON.
        writeInt16LE(data, 9, 0x7FFF);

        return data;
    }

    private byte[] buildIndoorBikeData() {
        byte[] data = new byte[12];

        // Flags
        data[0] = 0x64;
        data[1] = 0x02; // HR present

        // Speed (uint16 LE, 0.01 km/h)
        int speed = (int) (grpc.getLastSpeedKph() * 100);
        if (speed < 0) speed = 0;
        writeUint16LE(data, 2, speed);

        // Cadence (uint16 LE, 0.5 RPM)
        int cadence = (int) (grpc.getLastCadenceRpm() * 2);
        writeUint16LE(data, 4, cadence);

        // Resistance (uint16 LE)
        writeUint16LE(data, 6, 0);

        // Power (uint16 LE, watts)
        int power = (int) grpc.getLastWatts();
        writeUint16LE(data, 8, power);

        // Heart Rate
        data[10] = 0;

        // Padding
        data[11] = 0;

        return data;
    }

    private byte[] buildRscData() {
        byte[] data = new byte[10];

        // Flags: stride length + total distance present
        data[0] = 0x03;

        // Speed (uint16 LE, 1/256 m/s)
        double speedMs = grpc.getLastSpeedKph() / 3.6;
        int speedRaw = (int) (speedMs * 256);
        writeUint16LE(data, 1, speedRaw);

        // Cadence (uint8, RPM)
        data[3] = 0;

        // Stride length (uint16 LE, centimeters). The KICKR RUN sends 0 here when idle.
        writeUint16LE(data, 4, 0);

        // Total Distance (uint32 LE, 1/10 meters)
        int distDecimeters = (int) (grpc.getLastDistanceKm() * 10000);
        if (distDecimeters < 0) distDecimeters = 0;
        data[6] = (byte) (distDecimeters & 0xFF);
        data[7] = (byte) ((distDecimeters >> 8) & 0xFF);
        data[8] = (byte) ((distDecimeters >> 16) & 0xFF);
        data[9] = (byte) ((distDecimeters >> 24) & 0xFF);

        return data;
    }

    private byte[] buildHeartRateData() {
        return new byte[]{0x00, 0x00}; // Flags + 0 BPM
    }

    // --- mDNS Advertisement ---

    private void startMdns() {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);

            // Acquire multicast lock for mDNS
            multicastLock = wifiManager.createMulticastLock("NordicFTMS-DIRCON");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();

            // Get device IP address
            int ipInt = wifiManager.getConnectionInfo().getIpAddress();
            byte[] ipBytes = new byte[]{
                    (byte) (ipInt & 0xFF),
                    (byte) ((ipInt >> 8) & 0xFF),
                    (byte) ((ipInt >> 16) & 0xFF),
                    (byte) ((ipInt >> 24) & 0xFF)
            };
            InetAddress address = InetAddress.getByAddress(ipBytes);

            Log.i(LOG_TAG, "Starting mDNS on " + address.getHostAddress());

            jmdns = JmDNS.create(address, "NordicFTMS");

            // Build TXT record properties. Treadmills expose the KICKR RUN-style
            // Wahoo services; other machines publish a generic FTMS-only profile.
            Map<String, String> props = new HashMap<>();
            props.put("ble-service-uuids", getDirconBleServiceUuids());

            // Use WiFi MAC or a stable identifier
            String macAddress = getMacAddress(wifiManager);
            props.put("mac-address", macAddress);
            String dirconSerialNumber = getDirconSerialNumber();
            props.put("serial-number", dirconSerialNumber);

            String serviceName = getDirconServiceName(macAddress);

            ServiceInfo serviceInfo = ServiceInfo.create(
                    "_wahoo-fitness-tnp._tcp.local.",
                    serviceName,
                    DIRCON_PORT,
                    0, 0, props
            );

            jmdns.registerService(serviceInfo);
            Log.i(LOG_TAG, "mDNS registered as \"" + serviceName + "\" on port " + DIRCON_PORT
                    + " serial=" + dirconSerialNumber);

        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to start mDNS", e);
        }
    }

    private void stopMdns() {
        try {
            if (jmdns != null) {
                jmdns.unregisterAllServices();
                jmdns.close();
                jmdns = null;
            }
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error stopping mDNS", e);
        }
    }

    @SuppressWarnings("deprecation")
    private String getMacAddress(WifiManager wifiManager) {
        try {
            String mac = wifiManager.getConnectionInfo().getMacAddress();
            if (mac != null && !mac.equals("02:00:00:00:00:00")) {
                // Convert colons to hyphens to match KICKR RUN format
                return mac.toUpperCase().replace(':', '-');
            }
        } catch (Exception e) { /* fall through */ }
        // Generate a stable MAC from device properties (Android 6+ hides real MAC)
        try {
            String serial = android.os.Build.SERIAL != null ? android.os.Build.SERIAL : "NordicFTMS";
            byte[] hash = java.security.MessageDigest.getInstance("MD5").digest(serial.getBytes());
            return String.format("%02X-%02X-%02X-%02X-%02X-%02X",
                    hash[0], hash[1], hash[2], hash[3], hash[4], hash[5]);
        } catch (Exception e) {
            return "AA-BB-CC-DD-EE-FF";
        }
    }

    private double normalizeInclinePercent(double requestedInclinePercent) {
        double roundedInclinePercent = Math.round(requestedInclinePercent * 2.0) / 2.0;
        double minIncline = getMinInclinePercent();
        double maxIncline = getMaxInclinePercent();
        return Math.max(minIncline, Math.min(maxIncline, roundedInclinePercent));
    }

    private String getDirconSerialNumber() {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        String macAddress = getMacAddress(wifiManager);

        try {
            byte[] hash = java.security.MessageDigest.getInstance("MD5")
                    .digest(("NordicFTMS-DIRCON-" + macAddress).getBytes());
            long serialValue = ((long) (hash[0] & 0xFF) << 24)
                    | ((long) (hash[1] & 0xFF) << 16)
                    | ((long) (hash[2] & 0xFF) << 8)
                    | (hash[3] & 0xFF);
            // Use a stable synthetic 9-digit numeric serial that cannot be
            // confused with a copied hardware serial from a real device.
            return String.format("9%08d", serialValue % 100000000L);
        } catch (Exception e) {
            return "999999999";
        }
    }

    private String getDirconServiceName(String macAddress) {
        if (!isTreadmillDirconProfile()) {
            return GENERIC_DIRCON_SERVICE_NAME;
        }
        String macHex = macAddress.replace("-", "");
        String suffix = macHex.length() >= 4
                ? macHex.substring(macHex.length() - 4)
                : "ABCD";
        return "KICKR RUN " + suffix;
    }

    private String getDirconBleServiceUuids() {
        if (!isTreadmillDirconProfile()) {
            return "0x1826";
        }
        return "0x1826,0x1814,A026EE0E-0A7D-4AB3-97FA-F1500F9FEB8B";
    }

    private boolean isTreadmillDirconProfile() {
        return grpc != null && grpc.isTreadmillDevice();
    }

    // --- Packet Building ---

    private byte[] buildResponse(byte msgType, int seqNum, byte respCode, byte[] payload) {
        return buildPacket(msgType, seqNum, respCode, payload);
    }

    private byte[] buildPacket(byte msgType, int seqNum, byte respCode, byte[] payload) {
        byte[] packet = new byte[HEADER_LENGTH + payload.length];
        packet[0] = MSG_VERSION;
        packet[1] = msgType;
        packet[2] = (byte) (seqNum & 0xFF);
        packet[3] = respCode;
        // Length is big-endian
        packet[4] = (byte) ((payload.length >> 8) & 0xFF);
        packet[5] = (byte) (payload.length & 0xFF);
        if (payload.length > 0) {
            System.arraycopy(payload, 0, packet, HEADER_LENGTH, payload.length);
        }
        return packet;
    }

    // --- UUID Helpers ---

    /**
     * Convert a 16-bit BLE UUID to the 16-byte DIRCON wire format.
     */
    private static byte[] uuidToBytes(int uuid16) {
        return new byte[]{
                0x00, 0x00, (byte) ((uuid16 >> 8) & 0xFF), (byte) (uuid16 & 0xFF),
                0x00, 0x00, 0x10, 0x00,
                (byte) 0x80, 0x00, 0x00, (byte) 0x80, 0x5F, (byte) 0x9B, 0x34, (byte) 0xFB
        };
    }

    private void writeCharEntry(ByteArrayOutputStream out, int charUuid, int props) throws IOException {
        out.write(uuidToBytes(charUuid));
        out.write(props);
    }

    private void writeWahooCharEntry(ByteArrayOutputStream out, byte[] rawUuid, int props) throws IOException {
        out.write(rawUuid);
        out.write(props);
    }

    private void queuePostResponseNotification(ClientState client, int charUuid, byte[] data) {
        if (!client.notifySubscriptions.contains(charUuid)) return;
        synchronized (client.pendingPostResponseNotifications) {
            client.pendingPostResponseNotifications.add(
                    new PendingNotification(uuidToBytes(charUuid), data)
            );
        }
    }

    private void flushPostResponseNotificationsLocked(ClientState client) throws IOException {
        while (true) {
            PendingNotification pending;
            synchronized (client.pendingPostResponseNotifications) {
                pending = client.pendingPostResponseNotifications.poll();
            }
            if (pending == null) return;

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(pending.uuidBytes);
            payload.write(pending.data);
            byte[] packet = buildPacket(MSG_UNSOLICITED_NOTIFICATION, 0, RESP_SUCCESS, payload.toByteArray());
            client.outputStream.write(packet);
        }
    }

    // --- Byte Helpers ---

    private static void writeUint16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeInt16LE(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * @return true if there are any DIRCON clients currently connected
     */
    public boolean hasClients() {
        return !clients.isEmpty();
    }

    /**
     * Check if a raw 16-byte UUID matches a Wahoo proprietary characteristic.
     */
    private static boolean isWahooChar(byte[] rawUuid, byte[] wahooChar) {
        return java.util.Arrays.equals(rawUuid, wahooChar);
    }

    private static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
