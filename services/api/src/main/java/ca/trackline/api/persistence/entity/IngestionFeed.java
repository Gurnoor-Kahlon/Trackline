package ca.trackline.api.persistence.entity;

/**
 * The realtime feeds Trackline polls. Each is polled on its own schedule so a
 * slow fetch of one cannot delay another.
 */
public enum IngestionFeed {

    VEHICLE_POSITIONS,
    TRIP_UPDATES,
    ALERTS
}
