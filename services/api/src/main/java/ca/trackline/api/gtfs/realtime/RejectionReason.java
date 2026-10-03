package ca.trackline.api.gtfs.realtime;

/**
 * Why an entity was dropped.
 *
 * <p>Counted per cycle rather than merely logged. A feed that starts producing
 * rubbish shows up as a rising rejection rate long before anyone notices
 * something missing on a map, and a cycle rejecting most of its entities is marked
 * suspect instead of silently degrading the data.
 */
public enum RejectionReason {

    /** No position at all, so there is nothing to place. */
    MISSING_POSITION,

    /** A coordinate outside the service area, which is a feed error rather than a vehicle. */
    COORDINATES_OUT_OF_RANGE,

    /** No vehicle identifier, so the reading cannot be attributed or deduplicated. */
    MISSING_VEHICLE_ID,

    /** No timestamp, so the reading cannot be aged or deduplicated. */
    MISSING_TIMESTAMP,

    /** Reported from further in the future than clock skew explains. */
    TIMESTAMP_IN_FUTURE,

    /** Older than the window Trackline will accept as a current position. */
    TIMESTAMP_TOO_OLD,

    /** Names a route that is not in the imported schedule. */
    UNKNOWN_ROUTE,

    /** A trip update with no stop time updates describes nothing. */
    NO_STOP_TIME_UPDATES,

    /** An alert with no informed entity cannot be attributed to anything. */
    NO_INFORMED_ENTITY
}
