package com.nordicftms.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InclineCommandTrackerTest {
    private static final double TOLERANCE = 0.3;
    private static final long RETENTION_MS = 5000L;
    private static final int MAX_PENDING_TARGETS = 12;
    private static final long COMMAND_COOLDOWN_MS = 0L;

    @Test
    public void matchesRapidSuccessiveCommandTargets() {
        InclineCommandTracker tracker = new InclineCommandTracker(
                TOLERANCE,
                RETENTION_MS,
                MAX_PENDING_TARGETS,
                COMMAND_COOLDOWN_MS
        );

        assertEquals(
                InclineCommandTracker.ChangeResult.NO_CHANGE,
                tracker.updateObservedIncline(0.0, 0L)
        );

        tracker.setTargetIncline(2.0, 100L);
        tracker.setTargetIncline(4.0, 200L);
        tracker.setTargetIncline(6.0, 300L);

        assertEquals(
                InclineCommandTracker.ChangeResult.COMMAND_MATCH,
                tracker.updateObservedIncline(4.0, 400L)
        );
        assertEquals(
                InclineCommandTracker.ChangeResult.COMMAND_MATCH,
                tracker.updateObservedIncline(6.0, 500L)
        );
    }

    @Test
    public void reportsManualOverrideWhenObservedTargetWasNeverCommanded() {
        InclineCommandTracker tracker = new InclineCommandTracker(
                TOLERANCE,
                RETENTION_MS,
                MAX_PENDING_TARGETS,
                COMMAND_COOLDOWN_MS
        );

        assertEquals(
                InclineCommandTracker.ChangeResult.NO_CHANGE,
                tracker.updateObservedIncline(0.0, 0L)
        );

        tracker.setTargetIncline(2.0, 100L);
        tracker.setTargetIncline(4.0, 200L);

        assertEquals(
                InclineCommandTracker.ChangeResult.MANUAL_OVERRIDE,
                tracker.updateObservedIncline(3.0, 300L)
        );
    }

    @Test
    public void expiresOldCommandIntents() {
        InclineCommandTracker tracker = new InclineCommandTracker(
                TOLERANCE,
                RETENTION_MS,
                MAX_PENDING_TARGETS,
                COMMAND_COOLDOWN_MS
        );

        assertEquals(
                InclineCommandTracker.ChangeResult.NO_CHANGE,
                tracker.updateObservedIncline(0.0, 0L)
        );

        tracker.setTargetIncline(5.0, 100L);

        assertEquals(
                InclineCommandTracker.ChangeResult.MANUAL_OVERRIDE,
                tracker.updateObservedIncline(5.0, 5200L)
        );
    }

    @Test
    public void firstObservationConsumesMatchingPendingCommandWithoutManualOverride() {
        InclineCommandTracker tracker = new InclineCommandTracker(
                TOLERANCE,
                RETENTION_MS,
                MAX_PENDING_TARGETS,
                COMMAND_COOLDOWN_MS
        );

        tracker.setTargetIncline(3.0, 100L);

        assertEquals(
                InclineCommandTracker.ChangeResult.NO_CHANGE,
                tracker.updateObservedIncline(3.0, 200L)
        );
        assertEquals(
                InclineCommandTracker.ChangeResult.NO_CHANGE,
                tracker.updateObservedIncline(3.0, 300L)
        );
    }
}
