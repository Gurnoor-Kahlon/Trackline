package ca.trackline.api.vehicles;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;

/**
 * Shapes returned by the vehicle endpoints.
 */
public final class VehicleDtos {

    private VehicleDtos() {
    }

    /**
     * One live vehicle.
     *
     * <p>Every field is published by the TTC or absent from it. {@code speed} in
     * particular is the agency's own figure and is frequently zero; it is named for
     * its source so nothing downstream mistakes it for something Trackline derived.
     *
     * <p>There is no direction here. The vehicle feed does not carry one, and
     * inferring it belongs to the stage that projects vehicles onto route geometry —
     * guessing one at this layer would present an inference as a fact.
     *
     * @param vehicleId        the publisher's identifier
     * @param routeId          route being worked, or null when unassigned
     * @param latitude         degrees north
     * @param longitude        degrees east
     * @param bearing          degrees clockwise from north, or null
     * @param sourceSpeed      metres per second as published, or null
     * @param stopId           stop it is at or approaching, or null
     * @param occupancyStatus  GTFS occupancy ordinal, or null
     * @param reportedAt       when the vehicle reported
     * @param ageSeconds       how old this particular reading is
     */
    public record LiveVehicle(
            String vehicleId,
            String routeId,
            double latitude,
            double longitude,
            Float bearing,
            Float sourceSpeed,
            String stopId,
            Short occupancyStatus,
            Instant reportedAt,
            long ageSeconds) {

        static LiveVehicle from(VehicleSnapshot snapshot, Instant now) {
            return new LiveVehicle(
                    snapshot.vehicleId(),
                    snapshot.routeId(),
                    snapshot.latitude(),
                    snapshot.longitude(),
                    snapshot.bearing(),
                    snapshot.speed(),
                    snapshot.stopId(),
                    snapshot.occupancyStatus(),
                    snapshot.sourceTimestamp(),
                    Math.max(0, java.time.Duration.between(snapshot.sourceTimestamp(), now).toSeconds()));
        }
    }

    /**
     * A set of live vehicles.
     *
     * <p>{@code oldestReadingSeconds} is reported alongside the envelope's own feed
     * age because they answer different questions: the envelope says how old the
     * snapshot is, this says how stale the worst vehicle in it is. A parked vehicle
     * that reported eight minutes ago is in an otherwise current snapshot.
     *
     * @param vehicles             the vehicles
     * @param count                how many, so a client need not count them
     * @param oldestReadingSeconds age of the stalest reading, or null when empty
     */
    public record LiveVehicleSet(List<LiveVehicle> vehicles, int count, Long oldestReadingSeconds) {

        static LiveVehicleSet of(List<VehicleSnapshot> snapshots, Instant now) {
            List<LiveVehicle> vehicles = snapshots.stream()
                    .map(snapshot -> LiveVehicle.from(snapshot, now))
                    .toList();
            Long oldest = vehicles.stream()
                    .map(LiveVehicle::ageSeconds)
                    .max(Long::compareTo)
                    .orElse(null);
            return new LiveVehicleSet(vehicles, vehicles.size(), oldest);
        }
    }
}
