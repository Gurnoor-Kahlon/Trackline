package ca.trackline.api.stream;

import java.time.Instant;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;

/** Vehicles and metre offsets for the stream tests. */
final class StreamTestData {

    /** Downtown Toronto, so offsets in metres convert to degrees with Toronto's scale. */
    static final double LAT = 43.65;
    static final double LON = -79.38;

    private static final double METRES_PER_DEGREE_LATITUDE = 111_320;

    private StreamTestData() {
    }

    static Tracked tracked(String id, String route, double lat, double lon, long timestamp) {
        return new Tracked(id, route, lat, lon, 90f, 5f, (short) 1, timestamp);
    }

    static Tracked tracked(String id, String route, long timestamp) {
        return tracked(id, route, LAT, LON, timestamp);
    }

    static VehicleSnapshot snapshot(String id, String route, double lat, double lon, long timestamp) {
        return new VehicleSnapshot(
                id, route, null, lat, lon, 90f, 5f, null, null, null, (short) 1, Instant.ofEpochSecond(timestamp));
    }

    /** A latitude this many metres north of {@link #LAT}. */
    static double northMetres(double metres) {
        return LAT + metres / METRES_PER_DEGREE_LATITUDE;
    }
}
