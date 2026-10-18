package ca.trackline.api.analytics.bunching;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingDtos.BunchingEventView;
import ca.trackline.api.analytics.bunching.BunchingDtos.DirectionView;
import ca.trackline.api.analytics.bunching.BunchingDtos.RouteBunchingView;
import ca.trackline.api.analytics.bunching.BunchingModels.RouteSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.SpacingSnapshot;
import ca.trackline.api.analytics.health.RouteHealthStore;
import ca.trackline.api.analytics.health.RouteHealthStore.SpacingRead;
import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.BunchingQueryRepository;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bunching on one route: where its vehicles are, which are in a pack, and how long each pack has lasted.
 *
 * <p>The positions come from the last analysis, a cache read; the events come from the database,
 * which is their only home. A route with no vehicles right now still has an answer, an empty set of
 * directions, and still lists any events from the last day.
 */
@RestController
@RequestMapping("/api/v1/routes")
@Tag(name = "Bunching", description = "Vehicles running in a pack while a gap opens elsewhere on the route")
public class RouteBunchingController {

    /** How far back resolved bunches are listed. Older ones are history, kept but not shown here. */
    static final Duration RECENT = Duration.ofHours(24);

    /** Bounds the resolved history: a busy route resolves a bunch every few minutes. */
    static final int MAX_EVENTS = 25;

    private final RouteHealthStore store;
    private final BunchingQueryRepository events;
    private final RouteQueryRepository routes;
    private final Clock clock;

    public RouteBunchingController(
            RouteHealthStore store, BunchingQueryRepository events, RouteQueryRepository routes, Clock clock) {
        this.store = store;
        this.events = events;
        this.routes = routes;
        this.clock = clock;
    }

    @GetMapping("/{routeId}/bunching")
    @Operation(
            summary = "Where a route's vehicles are, which are bunched, and the bunches of the last day",
            description = "A bunch is vehicles running close together while a large gap opens elsewhere on the "
                    + "same direction; close together alone is not bunching. `directions` is the latest analysis, "
                    + "with each vehicle's place along the route and whether it is in a bunch, left out for being at "
                    + "a terminal, or left out for not reporting. `events` are bunches open now and those resolved in "
                    + "the last 24 hours, with the times Trackline first and last saw each.")
    public ApiEnvelope<RouteBunchingView> bunching(@PathVariable String routeId) {
        if (!routes.exists(routeId)) {
            throw new ResourceNotFoundException("Route", routeId);
        }
        Instant now = Instant.now(clock);
        SpacingRead read = store.readSpacing();
        List<BunchingEventView> history = events.findForRoute(routeId, now.minus(RECENT), MAX_EVENTS);

        SpacingSnapshot snapshot = read.snapshot();
        if (snapshot == null) {
            return ApiEnvelope.of(new RouteBunchingView(routeId, List.of(), history, null), ResponseMeta.noData(now));
        }

        List<DirectionView> directions = snapshot.routes().stream()
                .filter(route -> route.routeId().equals(routeId))
                .findFirst()
                .map(RouteSpacing::directions)
                .orElse(List.of())
                .stream()
                .map(DirectionView::of)
                .toList();

        ResponseMeta meta = snapshot.feedTimestamp() == null
                ? ResponseMeta.noData(now)
                : ResponseMeta.fromFeed(now, snapshot.feedTimestamp(), read.degraded(), read.source());
        return ApiEnvelope.of(new RouteBunchingView(routeId, directions, history, snapshot.computedAt()), meta);
    }
}
