package ca.trackline.api.analytics.spacing;

/**
 * What a direction's expected spacing was taken from.
 *
 * <p>Shared by route health and bunching detection, which must agree on it: they judge the
 * same gaps against the same yardstick.
 */
public enum SpacingBasis {

    /** The timetable's headway, turned into a distance with the route's measured speed. */
    SCHEDULE,

    /** No timetable comparison was possible, so spacing is judged against its own average. */
    OBSERVED
}
