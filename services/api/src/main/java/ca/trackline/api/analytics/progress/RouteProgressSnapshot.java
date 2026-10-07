package ca.trackline.api.analytics.progress;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The outcome of projecting every vehicle in one ingestion cycle.
 *
 * <p>Exactly one entry per vehicle. An empty snapshot is what a cycle carries when
 * projection failed, and it reads as "never projected" rather than as a lot of
 * vehicles with nothing to report.
 */
public record RouteProgressSnapshot(Map<String, RouteProgress> byVehicle) {

    public RouteProgressSnapshot {
        byVehicle = Map.copyOf(byVehicle);
    }

    /** What a cycle carries when projection did not run or did not finish. */
    public static RouteProgressSnapshot unprojected() {
        return new RouteProgressSnapshot(Map.of());
    }

    public Optional<RouteProgress> get(String vehicleId) {
        return Optional.ofNullable(byVehicle.get(vehicleId));
    }

    public int size() {
        return byVehicle.size();
    }

    /** How many vehicles landed in each status, for metrics and the cycle log line. */
    public Map<ProjectionStatus, Long> countsByStatus() {
        Map<ProjectionStatus, Long> counts = new EnumMap<>(ProjectionStatus.class);
        for (RouteProgress progress : byVehicle.values()) {
            counts.merge(progress.status(), 1L, Long::sum);
        }
        return counts;
    }

    /** How many unknown-direction vehicles each reason accounts for. */
    public Map<UnknownReason, Long> unknownReasons() {
        Map<UnknownReason, Long> counts = new EnumMap<>(UnknownReason.class);
        for (RouteProgress progress : byVehicle.values()) {
            if (progress.unknownReason() != null) {
                counts.merge(progress.unknownReason(), 1L, Long::sum);
            }
        }
        return counts;
    }
}
