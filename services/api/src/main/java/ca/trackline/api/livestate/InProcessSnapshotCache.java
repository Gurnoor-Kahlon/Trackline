package ca.trackline.api.livestate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import org.springframework.stereotype.Component;

/**
 * This process's own copy of the last snapshot it ingested.
 *
 * <p>The middle tier of the degradation chain, and the cheapest insurance in the
 * system: when Redis is unreachable, the instance that just polled the feed still
 * has the answer in memory and can serve it without touching the database.
 *
 * <p>Deliberately not durable and deliberately not shared. A restarted instance has
 * nothing here until its first cycle, and an instance that is not ingesting has
 * nothing at all — which is why it is a fallback rather than the primary store.
 */
@Component
public class InProcessSnapshotCache {

    private final AtomicReference<Snapshot> latest = new AtomicReference<>();

    public void store(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
        latest.set(new Snapshot(List.copyOf(vehicles), feedTimestamp));
    }

    public Optional<LiveVehicles> read() {
        Snapshot snapshot = latest.get();
        return snapshot == null
                ? Optional.empty()
                : Optional.of(LiveVehicles.cached(snapshot.vehicles(), snapshot.feedTimestamp()));
    }

    /** Clears the copy. Used by tests that need an instance with no memory. */
    public void forget() {
        latest.set(null);
    }

    private record Snapshot(List<VehicleSnapshot> vehicles, Instant feedTimestamp) {
    }
}
