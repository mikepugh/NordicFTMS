package com.nordicftms.app;

import android.content.Context;
import android.content.ContextWrapper;
import com.ifit.glassos.ConsoleInfo;
import com.ifit.glassos.ConsoleType;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import static org.junit.Assert.*;

public class GrpcControlServiceLifecycleTest {
    @Test
    public void cachedInfoCannotMakeANewEndpointReady() throws Exception {
        GrpcControlService service = createService();
        try {
            field("cachedConsoleInfo").set(service,
                    ConsoleInfo.newBuilder().setMachineType(ConsoleType.TREADMILL).build());
            disconnectForRetry(service);
            Method ready = GrpcControlService.class.getDeclaredMethod("hasUsableConsoleInfo");
            ready.setAccessible(true);
            assertEquals(false, ready.invoke(service));
            assertFalse(service.isConnected());
        } finally {
            stopExecutor(service);
        }
    }

    @Test
    public void disconnectRejectsLateConsoleInfoFromRetiredChannel() throws Exception {
        GrpcControlService service = createService();
        try {
            field("activeGeneration").setLong(service, 42L);
            ((AtomicLong) field("generationCounter").get(service)).set(42L);
            disconnectForRetry(service);

            Method apply = GrpcControlService.class.getDeclaredMethod("applyConsoleInfo",
                    String.class, ConsoleInfo.class, int.class, long.class);
            apply.setAccessible(true);
            assertEquals(false, apply.invoke(service, "ConsoleChanged",
                    ConsoleInfo.newBuilder().setMachineType(ConsoleType.TREADMILL).build(), 0, 42L));
            assertNull(service.getConsoleInfo());
            assertFalse(service.isConnected());
        } finally {
            stopExecutor(service);
        }
    }

    @Test
    public void disconnectCancelsStreamsWithoutWaitingForCallbacks() throws Exception {
        GrpcControlService service = createService();
        NonBlockingShutdownChannel channel = new NonBlockingShutdownChannel();
        try {
            field("channel").set(service, channel);
            disconnectForRetry(service);
            assertTrue(channel.cancelled);
            assertNull(field("channel").get(service));
        } finally {
            stopExecutor(service);
        }
    }

    private static GrpcControlService createService() {
        return new GrpcControlService(new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
        }, null);
    }

    private static Field field(String name) throws Exception {
        Field field = GrpcControlService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void disconnectForRetry(GrpcControlService service) throws Exception {
        Class reasonType = Class.forName(GrpcControlService.class.getName() + "$DisconnectReason");
        Method disconnect = GrpcControlService.class.getDeclaredMethod("disconnectInternal", boolean.class, reasonType);
        disconnect.setAccessible(true);
        disconnect.invoke(service, false, Enum.valueOf(reasonType, "CONNECT_RETRY"));
    }

    private static void stopExecutor(GrpcControlService service) throws Exception {
        ((ScheduledExecutorService) field("callbackExecutor").get(service)).shutdownNow();
    }

    private static final class NonBlockingShutdownChannel extends ManagedChannel {
        boolean cancelled;
        @Override public ManagedChannel shutdownNow() { cancelled = true; return this; }
        @Override public ManagedChannel shutdown() { throw new AssertionError("Streams require cancellation"); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw new AssertionError("Must not wait while holding the connection monitor");
        }
        @Override public boolean isShutdown() { return cancelled; }
        @Override public boolean isTerminated() { return cancelled; }
        @Override public String authority() { return "localhost"; }
        @Override public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            throw new AssertionError("No RPC is expected during shutdown");
        }
    }
}
