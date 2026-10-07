package ca.trackline.api.analytics.progress;

/**
 * What projecting a vehicle onto its route concluded.
 *
 * <p>Every vehicle gets exactly one. The set is closed and each value is a
 * positive statement: a vehicle that could not be placed says why, instead of
 * quietly carrying no progress figure that a later stage might read as zero. The
 * names are persisted, so they are part of the schema.
 */
public enum ProjectionStatus {

    /** Direction chosen because the vehicle's heading agrees with exactly one shape. */
    ASSIGNED_BY_BEARING,

    /** Direction chosen from how far the vehicle moved along each shape since its last reading. */
    ASSIGNED_BY_MOVEMENT,

    /** On the route, but nothing singled out a direction. Shown on a map, never used for spacing. */
    UNKNOWN_DIRECTION,

    /** Further than the permitted offset from every shape of its route. */
    OFF_ROUTE,

    /** The feed assigned this vehicle to no route, so there is nothing to project onto. */
    NO_ROUTE,

    /** The route has no usable shape. Real: one TTC route has none. */
    NO_GEOMETRY;

    public boolean isAssigned() {
        return this == ASSIGNED_BY_BEARING || this == ASSIGNED_BY_MOVEMENT;
    }
}
