package ca.trackline.api.livestate;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;

/**
 * A set of live vehicles together with where the answer came from.
 *
 * <p>Provenance travels with the data rather than being decided by the caller: a
 * controller cannot know whether the shared cache answered, this process answered
 * from memory, or the database was read as a last resort, and that difference is
 * exactly what the response's {@code meta} block has to tell a client.
 *
 * @param vehicles        the vehicles
 * @param feedTimestamp   the publisher's timestamp for the snapshot, or null if unknown
 * @param source          which tier answered
 * @param degraded        true when the preferred tier was unavailable
 */
public record LiveVehicles(
        List<VehicleSnapshot> vehicles, Instant feedTimestamp, DataSource source, boolean degraded) {

    public static LiveVehicles live(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
        return new LiveVehicles(vehicles, feedTimestamp, DataSource.LIVE, false);
    }

    /** Answered from this process's memory because the shared cache was unreachable. */
    public static LiveVehicles cached(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
        return new LiveVehicles(vehicles, feedTimestamp, DataSource.CACHED, true);
    }

    /** Reconstructed from stored observations. Correct, but as old as the last write. */
    public static LiveVehicles fromDatabase(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
        return new LiveVehicles(vehicles, feedTimestamp, DataSource.DB_FALLBACK, true);
    }

    /** Nothing has been ingested yet, which is not the same as no vehicles running. */
    public static LiveVehicles nothingObserved() {
        return new LiveVehicles(List.of(), null, DataSource.NO_DATA, false);
    }

    public LiveVehicles withVehicles(List<VehicleSnapshot> filtered) {
        return new LiveVehicles(filtered, feedTimestamp, source, degraded);
    }
}
