package ca.trackline.api.common.envelope;

/**
 * Where the data in a response actually came from. Exposed to clients so the UI
 * can distinguish live data from a fallback, rather than presenting all
 * responses as equally current.
 */
public enum DataSource {

    /** Served from the current realtime feed snapshot. */
    LIVE,

    /** Redis was unavailable; served from PostgreSQL instead. */
    DB_FALLBACK,

    /** Static GTFS data that has no realtime component. */
    STATIC,

    /** No data exists yet for this resource. Counts are not measurements. */
    NO_DATA
}
