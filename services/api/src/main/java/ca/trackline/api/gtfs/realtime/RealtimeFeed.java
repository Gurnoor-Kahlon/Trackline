package ca.trackline.api.gtfs.realtime;

import ca.trackline.api.persistence.entity.IngestionFeed;

/**
 * The three realtime feeds, each polled on its own schedule.
 *
 * <p>Separate schedules because they differ by more than two orders of magnitude
 * in size and by much more than that in how often they matter: vehicle positions
 * are 85 KB and move constantly, alerts are 2 KB and change a few times a day.
 */
public enum RealtimeFeed {

    VEHICLE_POSITIONS(IngestionFeed.VEHICLE_POSITIONS, "vehicles"),
    TRIP_UPDATES(IngestionFeed.TRIP_UPDATES, "trips"),
    ALERTS(IngestionFeed.ALERTS, "alerts");

    private final IngestionFeed persistedName;
    private final String path;

    RealtimeFeed(IngestionFeed persistedName, String path) {
        this.persistedName = persistedName;
        this.path = path;
    }

    public IngestionFeed persistedName() {
        return persistedName;
    }

    /** Path segment on the publisher, appended to the configured base URL. */
    public String path() {
        return path;
    }
}
