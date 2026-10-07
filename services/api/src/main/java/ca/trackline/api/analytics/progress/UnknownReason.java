package ca.trackline.api.analytics.progress;

/**
 * Why a vehicle that is on its route still has no direction.
 *
 * <p>Only reached after the heading failed to decide, so each value describes what
 * the second signal, movement, could not do. Not persisted — it is reported through
 * metrics and logs, where the question it answers is "is the unknown share a
 * property of the data or a bug".
 */
public enum UnknownReason {

    /** The heading was inconclusive and the vehicle has no recent earlier reading to compare. */
    NO_PREVIOUS_READING,

    /** There is an earlier reading, but the vehicle has not moved forward along any shape. */
    NO_FORWARD_MOVEMENT,

    /** The vehicle moved forward along more than one shape, so movement does not choose. */
    CONFLICTING_MOVEMENT
}
