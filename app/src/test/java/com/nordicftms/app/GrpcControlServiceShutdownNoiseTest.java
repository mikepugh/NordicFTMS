package com.nordicftms.app;

import org.junit.Test;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GrpcControlServiceShutdownNoiseTest {
    @Test
    public void suppressesLocalChannelShutdownNoise() {
        StatusRuntimeException shutdownError =
                Status.UNAVAILABLE.withDescription("Channel shutdownNow invoked").asRuntimeException();

        assertTrue(GrpcControlService.shouldSuppressChannelShutdownNoise(true, true, shutdownError));
        assertTrue(GrpcControlService.shouldSuppressChannelShutdownNoise(false, false, shutdownError));
        assertFalse(GrpcControlService.shouldSuppressChannelShutdownNoise(false, true, shutdownError));
    }

    @Test
    public void treatsTooManyPingsAsBackendUnavailableSignal() {
        StatusRuntimeException tooManyPings =
                Status.RESOURCE_EXHAUSTED
                        .withDescription("HTTP/2 error code: ENHANCE_YOUR_CALM (Bandwidth exhausted)\nReceived Goaway\ntoo_many_pings")
                        .asRuntimeException();

        assertTrue(GrpcControlService.isBackendUnavailableSignal(tooManyPings));
    }
}
