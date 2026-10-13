package ca.trackline.api.analytics.health;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import ca.trackline.api.analytics.health.HealthDtos.RouteHealthList;
import ca.trackline.api.analytics.health.HealthDtos.RouteHealthSummary;
import ca.trackline.api.analytics.health.HealthDtos.RouteHealthView;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.health.RouteHealthStore.HealthRead;
import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Route health: how evenly each route's vehicles are spaced right now.
 *
 * <p>Answered from the verdicts the last ingestion cycle produced, so a request costs a
 * cache read and never an analysis. The envelope's {@code meta} carries the age of the
 * vehicle feed the verdicts were computed from, and its {@code source} says whether the
 * shared cache or this instance's own copy answered.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Route health", description = "How evenly service is spaced on each route")
public class RouteHealthController {

    private final RouteHealthStore store;
    private final RouteQueryRepository routes;
    private final Clock clock;

    public RouteHealthController(RouteHealthStore store, RouteQueryRepository routes, Clock clock) {
        this.store = store;
        this.routes = routes;
        this.clock = clock;
    }

    @GetMapping("/routes/{routeId}/health")
    @Operation(
            summary = "One route's health, with the figures behind it",
            description = "A verdict (EXCELLENT, NORMAL, UNSTABLE, MAJOR_DISRUPTION or UNKNOWN), how far to trust "
                    + "it, reason codes, and for each direction the measured spacing. Based on how evenly vehicles "
                    + "are spaced along the route, not on delay: the TTC publishes none. UNKNOWN is a statement "
                    + "that there is no verdict, with the reason; it is not an error.")
    public ApiEnvelope<RouteHealthView> health(@PathVariable String routeId) {
        RouteSummary route = routes.findRoute(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", routeId));
        HealthRead read = store.read();
        Instant now = Instant.now(clock);

        if (read.snapshot() == null) {
            return ApiEnvelope.of(
                    RouteHealthView.unknown(routeId, ReasonCode.NOT_ANALYSED_YET, null), ResponseMeta.noData(now));
        }

        HealthSnapshot snapshot = read.snapshot();
        RouteHealth found = snapshot.routes().stream()
                .filter(candidate -> candidate.routeId().equals(routeId))
                .findFirst()
                .orElse(null);

        RouteHealthView view = found != null
                ? RouteHealthView.of(found, snapshot.computedAt())
                : RouteHealthView.unknown(
                        routeId,
                        // A subway line is never in the snapshot, and says why. Any other route
                        // absent from it simply has no vehicles to judge right now.
                        route.supportsRealtime() ? ReasonCode.INSUFFICIENT_VEHICLES : ReasonCode.NO_REALTIME_FEED,
                        snapshot.computedAt());
        return ApiEnvelope.of(view, meta(read, now));
    }

    @GetMapping("/health/routes")
    @Operation(
            summary = "The current verdict for every route that has vehicles",
            description = "Worst first. Routes with no vehicles are absent, which is not a verdict. Optionally "
                    + "narrowed to the given states.")
    public ApiEnvelope<RouteHealthList> all(@RequestParam(required = false) Set<RouteHealthState> state) {
        HealthRead read = store.read();
        Instant now = Instant.now(clock);
        if (read.snapshot() == null) {
            return ApiEnvelope.of(new RouteHealthList(List.of(), null), ResponseMeta.noData(now));
        }

        HealthSnapshot snapshot = read.snapshot();
        List<RouteHealth> wanted = snapshot.routes().stream()
                .filter(route -> state == null || state.isEmpty() || state.contains(route.state()))
                .toList();
        Map<String, RouteSummary> names = routes.findRoutesByIds(wanted.stream().map(RouteHealth::routeId).toList())
                .stream()
                .collect(Collectors.toMap(RouteSummary::routeId, Function.identity()));

        List<RouteHealthSummary> summaries = wanted.stream()
                // A route in the snapshot that the schedule no longer has cannot be named, so it is left out.
                .filter(route -> names.containsKey(route.routeId()))
                .map(route -> summarise(route, names.get(route.routeId())))
                .sorted(Comparator
                        // Worst first, no verdict last, and within a state the one judged on more vehicles first.
                        .comparingInt((RouteHealthSummary summary) -> rank(summary.state()))
                        .thenComparing(Comparator.comparing(RouteHealthSummary::vehicles).reversed()))
                .toList();

        return ApiEnvelope.of(new RouteHealthList(summaries, snapshot.computedAt()), meta(read, now));
    }

    private static RouteHealthSummary summarise(RouteHealth health, RouteSummary route) {
        int vehicles = health.directions().stream()
                .mapToInt((DirectionHealth direction) -> direction.metrics().vehicles())
                .sum();
        return new RouteHealthSummary(
                route.routeId(), route.shortName(), route.longName(), route.mode(), route.colour(),
                route.textColour(), health.state(), health.confidence(), health.reasons(), vehicles);
    }

    /** Sort key: the worst verdict first, a missing verdict last. */
    private static int rank(RouteHealthState state) {
        return state.isKnown() ? -state.severity() : 1;
    }

    private static ResponseMeta meta(HealthRead read, Instant now) {
        if (read.snapshot() == null || read.snapshot().feedTimestamp() == null) {
            return ResponseMeta.noData(now);
        }
        DataSource source = read.source();
        return ResponseMeta.fromFeed(now, read.snapshot().feedTimestamp(), read.degraded(), source);
    }
}
