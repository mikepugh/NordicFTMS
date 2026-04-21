package com.nordicftms.app;

/**
 * Shared tuning knobs for {@link InclineCommandTracker}. FTMSService and
 * DirconServer each own an independent tracker but must agree on these values
 * so both paths classify the same observation the same way.
 */
final class InclineTrackerConstants {
    // Match window around each pending target (percent). Covers the treadmill's
    // ~0.5% physical incline resolution plus small rounding/transit slop.
    static final double INCLINE_TOLERANCE = 0.75;

    // How long a pending command stays eligible for matching. Steep grades on
    // an incline trainer can take ~10s for the actuator to reach target, so the
    // window must exceed the worst-case physical travel time.
    static final long COMMAND_INTENT_RETENTION_MS = 12_000L;

    // Maximum outstanding pending targets before the oldest is dropped. Zwift
    // can burst multiple grade updates per second during steep transitions.
    static final int MAX_PENDING_INCLINE_TARGETS = 24;

    // Grace period after any command during which an unmatched observation is
    // treated as in-flight transit (NO_CHANGE) rather than a manual override.
    static final long COMMAND_COOLDOWN_MS = 3_000L;

    private InclineTrackerConstants() {
    }
}
