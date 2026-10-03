package ca.trackline.api.ingestion;

import ca.trackline.api.gtfs.realtime.FeedSnapshot;
import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.persistence.entity.IngestionRunStatus;

/**
 * What one cycle did.
 *
 * @param feed          which feed ran
 * @param status        outcome, including NO_CHANGE for a payload that had not moved on
 * @param snapshot      what was parsed, null when the cycle never got that far
 * @param stored        observations written
 * @param durationMs    wall-clock duration
 * @param failureReason short cause when the cycle failed, never a stack trace
 */
public record IngestionCycleResult(
        RealtimeFeed feed,
        IngestionRunStatus status,
        FeedSnapshot snapshot,
        int stored,
        long durationMs,
        String failureReason) {

    /**
     * A cycle that did not run because one was already in progress. Not recorded as
     * a run: nothing was attempted, and counting it would make a slow feed look like
     * a frequently failing one.
     */
    static IngestionCycleResult skipped(RealtimeFeed feed) {
        return new IngestionCycleResult(feed, null, null, 0, 0, null);
    }

    static IngestionCycleResult failed(RealtimeFeed feed, long durationMs, String reason) {
        return new IngestionCycleResult(feed, IngestionRunStatus.FAILED, null, 0, durationMs, reason);
    }

    static IngestionCycleResult noChange(RealtimeFeed feed, long durationMs, FeedSnapshot snapshot) {
        return new IngestionCycleResult(feed, IngestionRunStatus.NO_CHANGE, snapshot, 0, durationMs, null);
    }

    public boolean ran() {
        return status != null;
    }
}
