package ca.trackline.api.gtfs.realtime;

import java.time.Instant;

/**
 * One vehicle as the feed reported it, normalised but not interpreted.
 *
 * <p>Every field here is published by the TTC or absent from it. Nothing is
 * derived, defaulted or filled in: a null means the feed did not say, which is a
 * different claim from zero. Derived values — progress along a route, inferred
 * direction, spacing — are added by later stages and kept separate so it stays
 * obvious which numbers came from the agency.
 *
 * <p>Measured field population over a real snapshot of 1,363 vehicles: position,
 * bearing, speed, timestamp and vehicle id are always present; occupancy on 98.8%;
 * trip and route on 77%, because 313 vehicles were running without a trip
 * assignment. {@code directionId} is absent from this feed entirely, which is why
 * direction has to be inferred from geometry later.
 *
 * @param vehicleId          the publisher's identifier for the vehicle
 * @param routeId            route it is working, or null when unassigned
 * @param sourceTripId       the feed's trip id, opaque: it does not join to the schedule
 * @param latitude           degrees north
 * @param longitude          degrees east
 * @param bearing            degrees clockwise from north, or null
 * @param speed              as published, in metres per second; frequently zero
 * @param currentStatus      GTFS VehicleStopStatus ordinal, or null
 * @param currentStopSequence position in the trip, or null
 * @param stopId             the stop it is at or approaching, or null
 * @param occupancyStatus    GTFS OccupancyStatus ordinal, or null
 * @param sourceTimestamp    when the vehicle reported
 */
public record VehicleSnapshot(
        String vehicleId,
        String routeId,
        String sourceTripId,
        double latitude,
        double longitude,
        Float bearing,
        Float speed,
        Short currentStatus,
        Integer currentStopSequence,
        String stopId,
        Short occupancyStatus,
        Instant sourceTimestamp) {

    /**
     * Whether the feed said which route this vehicle is working.
     *
     * <p>Roughly a quarter of vehicles report no trip — deadheading, on a break, or
     * between assignments. They are kept rather than dropped: they are real
     * vehicles, and discarding them would understate the fleet.
     */
    public boolean hasRouteAssignment() {
        return routeId != null;
    }
}
