package ca.trackline.api.common.envelope;

/**
 * Where the data in a response actually came from. Exposed to clients so the UI
 * can distinguish live data from a fallback, rather than presenting all
 * responses as equally current.
 */
public enum DataSource {

    /** Served from the shared live-state cache, holding the current feed snapshot. */
    LIVE,

    /**
     * Served from this process's own copy of the last snapshot, because the shared
     * cache was unreachable. Current, but only as current as this instance is.
     */
    CACHED,

    /** Redis was unavailable; served from PostgreSQL instead. */
    DB_FALLBACK,

    /**
     * Read from Trackline's own database, which is the record for this data and has no
     * cache in front of it. Current as of the last ingestion cycle that was applied.
     */
    STORED,

    /** Static GTFS data that has no realtime component. */
    STATIC,

    /** No data exists yet for this resource. Counts are not measurements. */
    NO_DATA
}
