package ca.trackline.api.gtfs.schedule;

import java.time.LocalDate;
import java.util.Map;

/**
 * What an import did.
 *
 * @param status          whether the feed was loaded, already current, or failed
 * @param feedVersionId   the version row, null when nothing was written
 * @param sha256          digest of the archive, null when the download failed
 * @param rowCounts       rows loaded per table
 * @param feedStartDate   first day the imported schedule describes
 * @param feedEndDate     last day it describes
 * @param durationMs      wall-clock duration of the whole attempt
 * @param failureReason   short cause when status is FAILED, never a stack trace
 */
public record GtfsImportResult(
        GtfsImportStatus status,
        Long feedVersionId,
        String sha256,
        Map<String, Integer> rowCounts,
        LocalDate feedStartDate,
        LocalDate feedEndDate,
        long durationMs,
        String failureReason) {

    public static GtfsImportResult imported(
            long feedVersionId,
            String sha256,
            Map<String, Integer> rowCounts,
            LocalDate feedStartDate,
            LocalDate feedEndDate,
            long durationMs) {
        return new GtfsImportResult(
                GtfsImportStatus.IMPORTED, feedVersionId, sha256, rowCounts,
                feedStartDate, feedEndDate, durationMs, null);
    }

    public static GtfsImportResult unchanged(long feedVersionId, String sha256, long durationMs) {
        return new GtfsImportResult(
                GtfsImportStatus.UNCHANGED, feedVersionId, sha256, Map.of(), null, null, durationMs, null);
    }

    public static GtfsImportResult failed(String sha256, String reason, long durationMs) {
        return new GtfsImportResult(
                GtfsImportStatus.FAILED, null, sha256, Map.of(), null, null, durationMs, reason);
    }

    public boolean succeeded() {
        return status != GtfsImportStatus.FAILED;
    }
}
