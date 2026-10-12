package ca.trackline.api.stream;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;

/**
 * A vehicle as the stream describes it: only what a map needs to draw it.
 *
 * <p>Narrower than {@link VehicleSnapshot} on purpose. Every field here is sent to
 * every client for every change, so a field earns its place by being drawn or by
 * being needed to decide what to draw.
 *
 * @param id        the publisher's vehicle id
 * @param route     route being worked, or null when unassigned
 * @param lat       degrees north
 * @param lon       degrees east
 * @param bearing   degrees clockwise from north, or null
 * @param speed     metres per second as published, or null; frequently zero
 * @param occupancy GTFS occupancy ordinal, or null
 * @param timestamp when the vehicle reported, in epoch seconds
 */
public record Tracked(
        String id,
        String route,
        double lat,
        double lon,
        Float bearing,
        Float speed,
        Short occupancy,
        long timestamp) {

    public static Tracked from(VehicleSnapshot vehicle) {
        return new Tracked(
                vehicle.vehicleId(),
                vehicle.routeId(),
                vehicle.latitude(),
                vehicle.longitude(),
                vehicle.bearing(),
                vehicle.speed(),
                vehicle.occupancyStatus(),
                vehicle.sourceTimestamp().getEpochSecond());
    }

    public Where where() {
        return new Where(route, lat, lon);
    }
}
