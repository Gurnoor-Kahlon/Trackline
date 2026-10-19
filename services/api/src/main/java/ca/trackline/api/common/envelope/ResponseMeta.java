package ca.trackline.api.common.envelope;

import java.time.Duration;
import java.time.Instant;

/**
 * Freshness and provenance for a response payload.
 *
 * <p>Trackline never presents data as live without stating its age, so every
 * response carrying feed-derived data includes this block. {@code feedTimestamp}
 * and {@code ageSeconds} are null when no feed data underlies the response —
 * null means "unknown", never zero.
 *
 * @param generatedAt   when this response was assembled
 * @param feedTimestamp the source feed's own timestamp, or null if none applies
 * @param ageSeconds    seconds between {@code feedTimestamp} and
 *                      {@code generatedAt}, or null if unknown
 * @param stale         true when the data is too old to be presented as current
 * @param degraded      true when a dependency failed and a fallback was used
 * @param source        where the data came from
 */
public record ResponseMeta(
        Instant generatedAt,
        Instant feedTimestamp,
        Long ageSeconds,
        boolean stale,
        boolean degraded,
        DataSource source) {

    /**
     * Age beyond which feed-derived data is flagged stale. Chosen to be a few
     * multiples of the realtime feed's publish interval; the interval is
     * measured rather than assumed in the realtime ingestion milestone, and this
     * threshold is revisited then.
     */
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);

    /**
     * Meta for a response with no underlying feed data. Marked stale because
     * there is nothing current to show, but not degraded: nothing failed.
     */
    public static ResponseMeta noData(Instant generatedAt) {
        return new ResponseMeta(generatedAt, null, null, true, false, DataSource.NO_DATA);
    }

    /**
     * Meta for feed-derived data, deriving age and staleness from the feed's own
     * timestamp so callers cannot forget to compute them.
     */
    public static ResponseMeta fromFeed(
            Instant generatedAt, Instant feedTimestamp, boolean degraded, DataSource source) {
        long ageSeconds = Duration.between(feedTimestamp, generatedAt).toSeconds();
        boolean stale = ageSeconds > STALE_AFTER.toSeconds();
        return new ResponseMeta(generatedAt, feedTimestamp, ageSeconds, stale, degraded, source);
    }

    /**
     * Meta for figures computed from stored history. They describe a past window, not a feed's current
     * state, so there is no feed timestamp, and nothing about them goes stale in the way a live position does.
     */
    public static ResponseMeta stored(Instant generatedAt) {
        return new ResponseMeta(generatedAt, null, null, false, false, DataSource.STORED);
    }

    /** Meta for static GTFS data, which has no realtime freshness dimension. */
    public static ResponseMeta forStatic(Instant generatedAt) {
        return new ResponseMeta(generatedAt, null, null, false, false, DataSource.STATIC);
    }
}
