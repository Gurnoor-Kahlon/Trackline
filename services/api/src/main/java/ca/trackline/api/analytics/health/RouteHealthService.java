package ca.trackline.api.analytics.health;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionInputs;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.progress.RouteProgress;
import ca.trackline.api.analytics.progress.RouteProgressSnapshot;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.gtfs.schedule.ScheduleCoverageService;
import ca.trackline.api.gtfs.schedule.ScheduleCoverageService.ScheduleCoverage;
import ca.trackline.api.gtfs.schedule.derive.ScheduledHeadwayBuilder.DayType;
import ca.trackline.api.persistence.query.HealthReferenceRepository;
import ca.trackline.api.persistence.query.HealthReferenceRepository.DirectionKey;
import ca.trackline.api.persistence.query.HealthReferenceRepository.ShapeInfo;
import org.springframework.stereotype.Service;

/**
 * Turns one cycle's placed vehicles into a health verdict for every route that has any.
 *
 * <p>This is the seam between the data and the pure calculator. It decides which vehicles
 * count, assembles each direction's gaps and the timetable facts to compare them with, and
 * leaves the judging to {@link RouteHealthCalculator}.
 *
 * <p>A vehicle counts towards spacing only if it was placed in a direction, has reported
 * recently and is not parked at a terminal. Each exclusion is counted and returned, so a
 * verdict that rests on fewer vehicles than are on the road says why.
 *
 * <p>Only routes with at least one vehicle in the feed get an entry. A route with none is
 * not analysed, and the API answers for it on request as having no verdict.
 */
@Service
public class RouteHealthService {

    /** Service days and hours are Toronto's, not UTC's. */
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final HealthReferenceRepository reference;
    private final ScheduleCoverageService coverage;
    private final HealthProperties thresholds;
    private final RouteHealthCalculator calculator;
    private final RouteSpeedEstimator speeds;
    private final RouteHealthStore store;
    private final HealthAnalysisMetrics metrics;
    private final Clock clock;

    public RouteHealthService(
            HealthReferenceRepository reference,
            ScheduleCoverageService coverage,
            HealthProperties thresholds,
            RouteHealthStore store,
            HealthAnalysisMetrics metrics,
            Clock clock) {
        this.reference = reference;
        this.coverage = coverage;
        this.thresholds = thresholds;
        this.calculator = new RouteHealthCalculator(thresholds);
        this.speeds = new RouteSpeedEstimator(thresholds);
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Analyses a cycle and publishes the verdicts.
     *
     * @param progress where each vehicle is along its route; an empty snapshot, which is what
     *                 a failed projection leaves, yields no verdicts and is not published
     */
    public HealthSnapshot analyse(List<VehicleSnapshot> vehicles, RouteProgressSnapshot progress, Instant feedTimestamp) {
        return metrics.time(() -> {
            Instant now = Instant.now(clock);
            ZonedDateTime local = now.atZone(TORONTO);
            ScheduleCoverage schedule = coverage.coverageToday();

            Map<DirectionKey, ShapeInfo> shapes = reference.primaryShapes();
            Map<DirectionKey, Double> headways = schedule.covered()
                    ? reference.scheduledHeadwaySeconds(dayTypeOf(local.getDayOfWeek()), local.getHour())
                    : Map.of();

            HealthSnapshot snapshot =
                    compute(vehicles, progress, feedTimestamp, now, shapes, headways, schedule.covered());
            if (progress.size() > 0) {
                store.publish(snapshot);
                metrics.record(snapshot);
            }
            return snapshot;
        });
    }

    /** The analysis itself, with every outside fact passed in. */
    HealthSnapshot compute(
            List<VehicleSnapshot> vehicles,
            RouteProgressSnapshot progress,
            Instant feedTimestamp,
            Instant computedAt,
            Map<DirectionKey, ShapeInfo> shapes,
            Map<DirectionKey, Double> headways,
            boolean scheduleCovered) {

        Instant staleBefore = feedTimestamp.minus(thresholds.staleAfter());
        Map<String, RouteTally> byRoute = new TreeMap<>();

        for (VehicleSnapshot vehicle : distinct(vehicles)) {
            if (!vehicle.hasRouteAssignment()) {
                continue;
            }
            RouteProgress placed = progress.get(vehicle.vehicleId()).orElse(null);
            if (placed == null) {
                continue;
            }
            RouteTally route = byRoute.computeIfAbsent(vehicle.routeId(), id -> new RouteTally());
            if (!placed.status().isAssigned()) {
                route.unplaced++;
                continue;
            }

            boolean stale = vehicle.sourceTimestamp().isBefore(staleBefore);
            route.byDirection
                    .computeIfAbsent(placed.directionId(), id -> new ArrayList<>())
                    .add(new Placed(placed.progressMetres(), stale));
            if (!stale) {
                speeds.observe(
                        vehicle.vehicleId(), vehicle.routeId(), placed.directionId(), placed.progressMetres(),
                        vehicle.sourceTimestamp());
            }
        }
        speeds.prune(feedTimestamp);

        List<RouteHealth> routes = new ArrayList<>();
        for (Map.Entry<String, RouteTally> entry : byRoute.entrySet()) {
            String routeId = entry.getKey();
            List<DirectionHealth> directions = new ArrayList<>();
            for (Map.Entry<Short, List<Placed>> direction : new TreeMap<>(entry.getValue().byDirection).entrySet()) {
                DirectionKey key = new DirectionKey(routeId, direction.getKey());
                ShapeInfo shape = shapes.get(key);
                if (shape == null) {
                    // Cannot happen for a placed vehicle, which was projected onto this very shape;
                    // skipped rather than invented should the schedule change under a cycle.
                    continue;
                }
                directions.add(calculator.evaluate(inputs(key, direction.getValue(), shape, headways, scheduleCovered)));
            }
            routes.add(calculator.combine(routeId, directions, entry.getValue().unplaced));
        }
        return new HealthSnapshot(feedTimestamp, computedAt, scheduleCovered, routes);
    }

    private DirectionInputs inputs(
            DirectionKey key,
            List<Placed> placed,
            ShapeInfo shape,
            Map<DirectionKey, Double> headways,
            boolean scheduleCovered) {

        int stale = 0;
        int terminal = 0;
        List<Double> usable = new ArrayList<>();
        int fresh = 0;
        for (Placed vehicle : placed) {
            if (vehicle.stale()) {
                stale++;
                continue;
            }
            fresh++;
            if (atTerminal(vehicle.progressMetres(), shape)) {
                terminal++;
            } else {
                usable.add(vehicle.progressMetres());
            }
        }

        return new DirectionInputs(
                key.routeId(),
                key.directionId(),
                HeadwaySeries.gaps(usable, shape.lengthMetres(), shape.loop()),
                usable.size(),
                fresh,
                stale,
                terminal,
                shape.lengthMetres(),
                shape.loop(),
                scheduleCovered,
                headways.get(key),
                speeds.speedMetresPerSecond(key.routeId(), key.directionId()).orElse(null));
    }

    /**
     * Whether a vehicle is within the terminal zone of either end. Loops have no ends to
     * park at in the same sense, so none of their vehicles are set aside.
     */
    private boolean atTerminal(double progressMetres, ShapeInfo shape) {
        if (shape.loop() || thresholds.terminalZoneMetres() <= 0) {
            return false;
        }
        return progressMetres < thresholds.terminalZoneMetres()
                || progressMetres > shape.lengthMetres() - thresholds.terminalZoneMetres();
    }

    /**
     * One reading per vehicle id, the newest: the feed has been seen to repeat an id, and a
     * duplicate would be counted as a second vehicle at the same spot.
     */
    private static List<VehicleSnapshot> distinct(List<VehicleSnapshot> vehicles) {
        Map<String, VehicleSnapshot> newest = new LinkedHashMap<>();
        for (VehicleSnapshot vehicle : vehicles) {
            newest.merge(
                    vehicle.vehicleId(),
                    vehicle,
                    (kept, incoming) -> incoming.sourceTimestamp().isAfter(kept.sourceTimestamp()) ? incoming : kept);
        }
        return new ArrayList<>(newest.values());
    }

    /**
     * The timetable's day type for a weekday. Statutory holidays are treated as the weekday
     * they fall on, which is a stated limitation: the schedule is read at the hour, and a
     * holiday timetable is not distinguished.
     */
    static DayType dayTypeOf(DayOfWeek day) {
        return switch (day) {
            case SATURDAY -> DayType.SATURDAY;
            case SUNDAY -> DayType.SUNDAY;
            default -> DayType.WEEKDAY;
        };
    }

    private record Placed(double progressMetres, boolean stale) {
    }

    private static final class RouteTally {
        final Map<Short, List<Placed>> byDirection = new HashMap<>();
        int unplaced;
    }
}
