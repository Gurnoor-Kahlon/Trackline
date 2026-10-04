package ca.trackline.api.livestate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;

/**
 * The shared store of what is happening right now.
 *
 * <p>An interface so the rest of the application depends on the idea of live state
 * rather than on Redis. That matters less for swapping the implementation than for
 * the failure path: the reader can treat "the store is unavailable" as an ordinary
 * outcome instead of an exception leaking out of a vendor client.
 */
public interface LiveStateStore {

    /**
     * Replaces the current vehicle snapshot.
     *
     * <p>Replaces rather than merges: the feed publishes complete datasets, so a
     * vehicle absent from the newest payload is no longer reporting and should stop
     * being shown, not linger from an earlier write.
     */
    void replaceVehicles(List<VehicleSnapshot> vehicles, Instant feedTimestamp);

    /**
     * @return the current snapshot, or empty when the store has nothing or is
     *         unreachable — the caller cannot tell the difference and does not need
     *         to, because both mean "ask the next tier"
     */
    Optional<LiveVehicles> readVehicles();

}
