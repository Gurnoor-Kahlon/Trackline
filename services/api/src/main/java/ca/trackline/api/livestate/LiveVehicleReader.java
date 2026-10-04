package ca.trackline.api.livestate;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.persistence.query.VehicleObservationQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers "where are the vehicles" from the best tier that can.
 *
 * <p>Three tiers, tried in order: the shared cache, this process's own copy, then
 * the stored observations. Each is worse than the one before — staler, or narrower,
 * or more expensive — and each is better than an error page. A rider does not care
 * which one answered; they care that the map still draws and that the screen admits
 * how old what it shows is.
 *
 * <p>The database tier is the one that earns this design. It is a real query against
 * millions of partitioned rows, so it must not be the normal path — but when Redis
 * is down it is the difference between degraded service and none.
 */
@Service
public class LiveVehicleReader {

    private static final Logger log = LoggerFactory.getLogger(LiveVehicleReader.class);

    private final LiveStateStore liveState;
    private final InProcessSnapshotCache inProcess;
    private final VehicleObservationQueryRepository observations;

    public LiveVehicleReader(
            LiveStateStore liveState,
            InProcessSnapshotCache inProcess,
            VehicleObservationQueryRepository observations) {
        this.liveState = liveState;
        this.inProcess = inProcess;
        this.observations = observations;
    }

    /** Every vehicle currently being tracked. */
    public LiveVehicles readAll() {
        Optional<LiveVehicles> fromCache = liveState.readVehicles();
        if (fromCache.isPresent()) {
            return fromCache.get();
        }

        Optional<LiveVehicles> fromMemory = inProcess.read();
        if (fromMemory.isPresent()) {
            log.debug("Live snapshot unavailable; serving this instance's own copy");
            return fromMemory.get();
        }

        return observations.latestVehicles().orElseGet(LiveVehicles::nothingObserved);
    }

    /**
     * Vehicles working one route.
     *
     * <p>Filtered after reading rather than stored per route. The snapshot is a few
     * hundred kilobytes and the filter is a scan of a list already in memory, so a
     * key per route would add write amplification and a consistency problem — a
     * partially rewritten set of route keys would mix two feed cycles — to save
     * nothing a client would notice.
     */
    public LiveVehicles readForRoute(String routeId) {
        return filter(readAll(), vehicle -> routeId.equals(vehicle.routeId()));
    }

    /**
     * Vehicles inside a bounding box.
     *
     * @param minLongitude west edge
     * @param minLatitude  south edge
     * @param maxLongitude east edge
     * @param maxLatitude  north edge
     */
    public LiveVehicles readInBoundingBox(
            double minLongitude, double minLatitude, double maxLongitude, double maxLatitude) {
        return filter(
                readAll(),
                vehicle -> vehicle.longitude() >= minLongitude
                        && vehicle.longitude() <= maxLongitude
                        && vehicle.latitude() >= minLatitude
                        && vehicle.latitude() <= maxLatitude);
    }

    private static LiveVehicles filter(LiveVehicles source, Predicate<VehicleSnapshot> keep) {
        List<VehicleSnapshot> kept = source.vehicles().stream().filter(keep).toList();
        return source.withVehicles(kept);
    }
}
