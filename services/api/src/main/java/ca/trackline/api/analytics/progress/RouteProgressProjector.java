package ca.trackline.api.analytics.progress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.persistence.query.PreviousReadingRepository;
import ca.trackline.api.persistence.query.PreviousReadingRepository.PreviousReading;
import ca.trackline.api.persistence.query.ShapeProjectionRepository;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ProjectionPoint;
import ca.trackline.api.persistence.query.ShapeProjectionRepository.ShapeProjection;
import org.springframework.stereotype.Service;

/**
 * Places every vehicle in a snapshot on its route.
 *
 * <p>This is the seam between the database and the inference. The database does
 * the geometry it is good at, for the whole snapshot in one statement; the
 * decision about direction stays in {@link DirectionInferrer}, which never sees a
 * row. What is left here is only the bookkeeping: which vehicles have a route,
 * which have a usable earlier reading, and making sure every vehicle ends up with
 * exactly one stated outcome.
 */
@Service
public class RouteProgressProjector {

    private final ShapeProjectionRepository shapes;
    private final PreviousReadingRepository previousReadings;
    private final DirectionInferrer inferrer;
    private final ProgressProperties properties;
    private final ProgressMetrics metrics;

    public RouteProgressProjector(
            ShapeProjectionRepository shapes,
            PreviousReadingRepository previousReadings,
            ProgressProperties properties,
            ProgressMetrics metrics) {
        this.shapes = shapes;
        this.previousReadings = previousReadings;
        this.properties = properties;
        this.inferrer = new DirectionInferrer(properties);
        this.metrics = metrics;
    }

    /**
     * @param vehicles one cycle's readings
     * @return one outcome per distinct vehicle id
     */
    public RouteProgressSnapshot project(List<VehicleSnapshot> vehicles) {
        return metrics.time(() -> {
            RouteProgressSnapshot snapshot = place(distinctVehicles(vehicles));
            metrics.record(snapshot);
            return snapshot;
        });
    }

    private RouteProgressSnapshot place(Map<String, VehicleSnapshot> vehicles) {
        Map<String, RouteProgress> outcomes = new HashMap<>();

        List<VehicleSnapshot> routed = new ArrayList<>();
        for (VehicleSnapshot vehicle : vehicles.values()) {
            if (vehicle.hasRouteAssignment()) {
                routed.add(vehicle);
            } else {
                outcomes.put(vehicle.vehicleId(), RouteProgress.noRoute(vehicle.vehicleId()));
            }
        }

        Map<String, PreviousReading> previous = previousReadings.latestBefore(routed, properties.maxPreviousAge());
        List<ProjectionPoint> points = routed.stream().map(vehicle -> toPoint(vehicle, previous)).toList();

        Map<String, List<ShapeCandidate>> candidates = new HashMap<>();
        for (ShapeProjection row : shapes.project(points, properties.tangentWindowMetres())) {
            candidates.computeIfAbsent(row.vehicleId(), id -> new ArrayList<>()).add(toCandidate(row));
        }

        for (VehicleSnapshot vehicle : routed) {
            List<ShapeCandidate> forVehicle = candidates.get(vehicle.vehicleId());
            outcomes.put(
                    vehicle.vehicleId(),
                    forVehicle == null
                            // The route exists but has no primary shape: real for one
                            // TTC route, and a different fact from being off one.
                            ? RouteProgress.noGeometry(vehicle.vehicleId())
                            : inferrer.infer(vehicle.vehicleId(), vehicle.bearing(), forVehicle));
        }

        return new RouteProgressSnapshot(outcomes);
    }

    /**
     * One reading per vehicle id, the newest.
     *
     * <p>The feed has been seen to repeat a vehicle id, and a duplicate here would
     * contribute a second set of candidate shapes to the same vehicle — four
     * candidates for a two-direction route, which the inference was never meant to
     * weigh. Keeping the newest reading is the same choice every later stage makes.
     */
    private static Map<String, VehicleSnapshot> distinctVehicles(List<VehicleSnapshot> vehicles) {
        Map<String, VehicleSnapshot> distinct = new LinkedHashMap<>();
        for (VehicleSnapshot vehicle : vehicles) {
            distinct.merge(
                    vehicle.vehicleId(),
                    vehicle,
                    (kept, incoming) -> incoming.sourceTimestamp().isAfter(kept.sourceTimestamp()) ? incoming : kept);
        }
        return distinct;
    }

    private static ProjectionPoint toPoint(VehicleSnapshot vehicle, Map<String, PreviousReading> previous) {
        PreviousReading earlier = previous.get(vehicle.vehicleId());
        // A previous reading on a different route says nothing about movement along
        // this one: the vehicle changed assignment between the two, and projecting
        // its old position onto the new route's shapes would manufacture a jump.
        boolean usable = earlier != null && vehicle.routeId().equals(earlier.routeId());

        return new ProjectionPoint(
                vehicle.vehicleId(),
                vehicle.routeId(),
                vehicle.latitude(),
                vehicle.longitude(),
                usable ? earlier.latitude() : null,
                usable ? earlier.longitude() : null);
    }

    private static ShapeCandidate toCandidate(ShapeProjection row) {
        return new ShapeCandidate(
                row.directionId(),
                row.offsetMetres(),
                row.fraction(),
                row.lengthMetres(),
                row.loop(),
                row.tangentBearing(),
                row.previousFraction());
    }
}
