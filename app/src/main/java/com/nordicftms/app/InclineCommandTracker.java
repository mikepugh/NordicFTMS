package com.nordicftms.app;

import java.util.ArrayDeque;

/**
 * Tracks recent remote incline intents and matches them against GlassOS target
 * incline updates. The treadmill reports target incline changes rather than
 * intermediate movement, so a reported incline should be treated as remote
 * control only if it matches a recent command we actually sent.
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

    private double lastObservedIncline = Double.NaN;
    private final ArrayDeque<PendingTarget> pendingTargets = new ArrayDeque<>();

    InclineCommandTracker(
            double inclineTolerance,
            long commandRetentionMs,
            int maxPendingTargets
    ) {
        this.inclineTolerance = inclineTolerance;
        this.commandRetentionMs = commandRetentionMs;
        this.maxPendingTargets = maxPendingTargets;
    }

    void setTargetIncline(double incline, long nowMs) {
        pruneExpiredTargets(nowMs);
        pendingTargets.addLast(new PendingTarget(incline, nowMs));
        while (pendingTargets.size() > maxPendingTargets) {
            pendingTargets.removeFirst();
        }
    }

    ChangeResult updateObservedIncline(double currentIncline, long nowMs) {
        pruneExpiredTargets(nowMs);

        if (Double.isNaN(lastObservedIncline)) {
            lastObservedIncline = currentIncline;
            consumeMatchingTarget(currentIncline);
            return ChangeResult.NO_CHANGE;
        }

        if (Double.compare(currentIncline, lastObservedIncline) == 0) {
            return ChangeResult.NO_CHANGE;
        }

        lastObservedIncline = currentIncline;
        if (consumeMatchingTarget(currentIncline)) {
            return ChangeResult.COMMAND_MATCH;
        }

        return ChangeResult.MANUAL_OVERRIDE;
    }

    private boolean consumeMatchingTarget(double currentIncline) {
        int matchedIndex = -1;
        int index = 0;
        for (PendingTarget pendingTarget : pendingTargets) {
            if (Math.abs(currentIncline - pendingTarget.inclinePercent) <= inclineTolerance) {
                matchedIndex = index;
                break;
            }
            index++;
        }

        if (matchedIndex < 0) {
            return false;
        }

        for (int i = 0; i <= matchedIndex; i++) {
            pendingTargets.removeFirst();
        }
        return true;
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
