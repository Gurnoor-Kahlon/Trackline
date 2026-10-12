package ca.trackline.api.vehicles;

import java.time.Clock;
import java.time.Instant;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.geo.BoundingBox;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.vehicles.VehicleDtos.LiveVehicleSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live vehicle positions.
 *
 * <p>Unlike the schedule endpoints these carry a real feed age, and the envelope's
 * {@code source} says which tier answered. A client that ignores it will still work
 * and will occasionally show data from a fallback without knowing — which is why
 * the frontend is built against this contract before it is built against the map.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Vehicles", description = "Live vehicle positions")
public class VehicleController {

    private final LiveVehicleReader vehicles;
    private final RouteQueryRepository routes;
    private final Clock clock;

    public VehicleController(LiveVehicleReader vehicles, RouteQueryRepository routes, Clock clock) {
        this.vehicles = vehicles;
        this.routes = routes;
        this.clock = clock;
    }

    @GetMapping("/vehicles")
    @Operation(
            summary = "Live vehicles, optionally filtered",
            description = "Without a filter this returns every tracked vehicle, which is roughly 650 "
                    + "overnight and 1,400 at midday. Check meta.source: LIVE is the shared cache, "
                    + "CACHED is one instance's own copy, DB_FALLBACK is reconstructed from stored "
                    + "observations and is the stalest of the three.")
    public ApiEnvelope<LiveVehicleSet> list(
            @RequestParam(required = false) String route,
            @RequestParam(required = false)
                    @Pattern(regexp = BoundingBox.FORMAT, message = "must be minLon,minLat,maxLon,maxLat")
                    String bbox) {

        LiveVehicles live;
        if (route != null) {
            requireRoute(route);
            live = vehicles.readForRoute(route);
        } else if (bbox != null) {
            live = readBoundingBox(bbox);
        } else {
            live = vehicles.readAll();
        }

        return envelope(live);
    }

    @GetMapping("/routes/{routeId}/vehicles")
    @Operation(
            summary = "Live vehicles working one route",
            description = "An empty list means no vehicles are running, which for a subway route is "
                    + "always true: the TTC publishes no subway vehicle positions.")
    public ApiEnvelope<LiveVehicleSet> forRoute(@PathVariable String routeId) {
        requireRoute(routeId);
        return envelope(vehicles.readForRoute(routeId));
    }

    private LiveVehicles readBoundingBox(String bbox) {
        BoundingBox box = BoundingBox.parse(bbox);
        return vehicles.readInBoundingBox(
                box.minLongitude(), box.minLatitude(), box.maxLongitude(), box.maxLatitude());
    }

    private void requireRoute(String routeId) {
        if (!routes.exists(routeId)) {
            throw new ResourceNotFoundException("Route", routeId);
        }
    }

    private ApiEnvelope<LiveVehicleSet> envelope(LiveVehicles live) {
        Instant now = Instant.now(clock);
        LiveVehicleSet payload = LiveVehicleSet.of(live.vehicles(), now);

        // No feed timestamp means nothing has been ingested, which is a different
        // claim from "the feed says there are no vehicles".
        ResponseMeta meta = live.feedTimestamp() == null
                ? ResponseMeta.noData(now)
                : ResponseMeta.fromFeed(now, live.feedTimestamp(), live.degraded(), live.source());

        return ApiEnvelope.of(payload, meta);
    }
}
