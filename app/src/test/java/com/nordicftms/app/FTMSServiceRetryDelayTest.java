package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FTMSServiceRetryDelayTest {
    @Test
    public void usesExpectedGrpcRetryBackoffSchedule() {
        assertEquals(1000L, FTMSService.getGrpcRetryDelayMs(0));
        assertEquals(2000L, FTMSService.getGrpcRetryDelayMs(1));
        assertEquals(5000L, FTMSService.getGrpcRetryDelayMs(2));
        assertEquals(10000L, FTMSService.getGrpcRetryDelayMs(3));
        assertEquals(10000L, FTMSService.getGrpcRetryDelayMs(9));
    }
}
