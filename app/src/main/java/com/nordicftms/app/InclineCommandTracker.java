package com.nordicftms.app;

import java.util.ArrayDeque;

/**
 * Tracks recent remote incline intents and matches them against GlassOS target
 * incline updates. The treadmill reports target incline changes rather than
 * intermediate movement, so a reported incline should be treated as remote
 * control only if it matches a recent command we actually sent.
 *
 * <p>Callers must pass a monotonic clock (e.g. {@code SystemClock.elapsedRealtime()})
 * as {@code nowMs}. Wall-clock time can jump on Android and would break the
 * retention and cooldown windows.
 */
final class InclineCommandTracker {
    enum ChangeResult {
        NO_CHANGE,
        COMMAND_MATCH,
        MANUAL_OVERRIDE
    }

    private final double inclineTolerance;
    private final long commandRetentionMs;
    private final int maxPendingTargets;
    private final long commandCooldownMs;

    private double lastObservedIncline = Double.NaN;
    private long lastCommandMs = Long.MIN_VALUE;
    private final ArrayDeque<PendingTarget> pendingTargets = new ArrayDeque<>();

    InclineCommandTracker(
            double inclineTolerance,
            long commandRetentionMs,
            int maxPendingTargets,
            long commandCooldownMs
    ) {
        this.inclineTolerance = inclineTolerance;
        this.commandRetentionMs = commandRetentionMs;
        this.maxPendingTargets = maxPendingTargets;
        this.commandCooldownMs = commandCooldownMs;
    }

    void setTargetIncline(double incline, long nowMs) {
        pruneExpiredTargets(nowMs);
        pendingTargets.addLast(new PendingTarget(incline, nowMs));
        while (pendingTargets.size() > maxPendingTargets) {
            pendingTargets.removeFirst();
        }
        lastCommandMs = nowMs;
    }

    ChangeResult updateObservedIncline(double currentIncline, long nowMs) {
        pruneExpiredTargets(nowMs);

        if (Double.isNaN(lastObservedIncline)) {
            lastObservedIncline = currentIncline;
            consumeClosestMatchingTarget(currentIncline);
            return ChangeResult.NO_CHANGE;
        }

        if (Double.compare(currentIncline, lastObservedIncline) == 0) {
            return ChangeResult.NO_CHANGE;
        }

        lastObservedIncline = currentIncline;
        if (consumeClosestMatchingTarget(currentIncline)) {
            return ChangeResult.COMMAND_MATCH;
        }

        if (lastCommandMs != Long.MIN_VALUE
                && nowMs - lastCommandMs < commandCooldownMs) {
            return ChangeResult.NO_CHANGE;
        }

        return ChangeResult.MANUAL_OVERRIDE;
    }

    /**
     * Find the pending target closest to the observed value and, if within
     * tolerance, consume it (and drop any older targets before it).
     *
     * <p>Picking the closest target — rather than the first within tolerance —
     * prevents an early stale target from spuriously matching a later, unrelated
     * observation, which would otherwise leave the newer real target stuck in
     * the queue and cause a future real manual press to be masked.
     */
    private boolean consumeClosestMatchingTarget(double currentIncline) {
        int bestIndex = -1;
        double bestDelta = Double.POSITIVE_INFINITY;
        int index = 0;
        for (PendingTarget pendingTarget : pendingTargets) {
            double delta = Math.abs(currentIncline - pendingTarget.inclinePercent);
            if (delta <= inclineTolerance && delta < bestDelta) {
                bestDelta = delta;
                bestIndex = index;
            }
            index++;
        }

        if (bestIndex < 0) {
            return false;
        }

        for (int i = 0; i <= bestIndex; i++) {
            pendingTargets.removeFirst();
        }
        return true;
    }

    int getPendingCount() {
        return pendingTargets.size();
    }

    /**
     * Closest pending target's value. Returns NaN when the queue is empty.
     * Does not consume any targets.
     */
    double getClosestPendingValue(double observedIncline) {
        double bestValue = Double.NaN;
        double bestDelta = Double.POSITIVE_INFINITY;
        for (PendingTarget pendingTarget : pendingTargets) {
            double delta = Math.abs(observedIncline - pendingTarget.inclinePercent);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestValue = pendingTarget.inclinePercent;
            }
        }
        return bestValue;
    }

    /**
     * Absolute delta from the observed value to the closest pending target.
     * Returns NaN when the queue is empty.
     */
    double getClosestPendingDelta(double observedIncline) {
        double bestDelta = Double.NaN;
        for (PendingTarget pendingTarget : pendingTargets) {
            double delta = Math.abs(observedIncline - pendingTarget.inclinePercent);
            if (Double.isNaN(bestDelta) || delta < bestDelta) {
                bestDelta = delta;
            }
        }
        return bestDelta;
    }

    /**
     * Age of the oldest pending command in milliseconds. Returns -1 when the
     * queue is empty.
     */
    long getOldestPendingAgeMs(long nowMs) {
        if (pendingTargets.isEmpty()) {
            return -1L;
        }
        return nowMs - pendingTargets.peekFirst().timestampMs;
    }

    /**
     * Milliseconds since the most recent command was registered. Returns
     * {@link Long#MAX_VALUE} when no commands have been registered.
     */
    long getMsSinceLastCommand(long nowMs) {
        if (lastCommandMs == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return nowMs - lastCommandMs;
    }

    private void pruneExpiredTargets(long nowMs) {
        while (!pendingTargets.isEmpty()
                && nowMs - pendingTargets.peekFirst().timestampMs > commandRetentionMs) {
            pendingTargets.removeFirst();
        }
    }

    private static final class PendingTarget {
        final double inclinePercent;
        final long timestampMs;

        PendingTarget(double inclinePercent, long timestampMs) {
            this.inclinePercent = inclinePercent;
            this.timestampMs = timestampMs;
        }
    }
}
