package ca.trackline.api.routes;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.Page;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.persistence.query.RouteQueryRepository.ShapeRow;
import ca.trackline.api.routes.RouteDtos.RouteDetail;
import ca.trackline.api.routes.RouteDtos.RouteShape;
import ca.trackline.api.routes.RouteDtos.RouteStop;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Routes and their geometry.
 *
 * <p>Everything here is static schedule data, so responses are marked
 * {@code STATIC}: there is no feed age to report, and claiming one would be
 * misleading. Live figures arrive on their own endpoints.
 */
@RestController
@RequestMapping("/api/v1/routes")
@Validated
@Tag(name = "Routes", description = "Routes, their directions, geometry and stops")
public class RouteController {

    /** Caps keep one request from asking for the whole table. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Largest simplification a caller may ask for. Beyond roughly 100 m a route
     * stops following the street it runs on, which would be worse than useless on
     * a map.
     */
    private static final int MAX_SIMPLIFY_METRES = 100;

    private final RouteQueryRepository routes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RouteController(RouteQueryRepository routes, ObjectMapper objectMapper, Clock clock) {
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "List routes", description = "Filterable by mode and by a substring of the name.")
    public ApiEnvelope<Page<RouteSummary>> list(
            @RequestParam(required = false) TransitMode mode,
            @RequestParam(required = false) @Size(min = 1, max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        long total = routes.countRoutes(mode, q);
        List<RouteSummary> items = total == 0 ? List.of() : routes.findRoutes(mode, q, page, size);

        return ApiEnvelope.of(new Page<>(items, page, size, total), staticMeta());
    }

    @GetMapping("/{routeId}")
    @Operation(summary = "A route and its directions")
    public ApiEnvelope<RouteDetail> detail(@PathVariable String routeId) {
        RouteSummary summary = routes.findRoute(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", routeId));

        return ApiEnvelope.of(
                new RouteDetail(
                        summary.routeId(),
                        summary.shortName(),
                        summary.longName(),
                        summary.mode(),
                        summary.colour(),
                        summary.textColour(),
                        summary.supportsRealtime(),
                        routes.findDirections(routeId)),
                staticMeta());
    }

    @GetMapping("/{routeId}/shape")
    @Operation(
            summary = "A route's geometry as GeoJSON",
            description = "Returns the primary shape for the direction: the variant the most trips use, "
                    + "which is the geometry live vehicles are projected onto.")
    public ApiEnvelope<RouteShape> shape(
            @PathVariable String routeId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1) short direction,
            @RequestParam(required = false) @Min(1) @Max(MAX_SIMPLIFY_METRES) Integer simplifyMetres) {

        requireRoute(routeId);

        ShapeRow row = routes.findShape(routeId, direction, simplifyMetres)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shape for route %s direction".formatted(routeId), String.valueOf(direction)));

        return ApiEnvelope.of(
                new RouteShape(
                        routeId, direction, row.shapeId(), row.lengthMetres(),
                        simplifyMetres, readGeoJson(row.geoJson())),
                staticMeta());
    }

    @GetMapping("/{routeId}/stops")
    @Operation(
            summary = "The stops a route serves, in order",
            description = "Taken from the longest scheduled pattern in that direction, since a route "
                    + "has several and only the schedule can say which stops belong to it.")
    public ApiEnvelope<List<RouteStop>> stops(
            @PathVariable String routeId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1) short direction) {

        requireRoute(routeId);
        return ApiEnvelope.of(routes.findStops(routeId, direction), staticMeta());
    }

    private void requireRoute(String routeId) {
        if (!routes.exists(routeId)) {
            throw new ResourceNotFoundException("Route", routeId);
        }
    }

    /**
     * PostGIS already rendered the geometry as GeoJSON, so it is parsed back into a
     * tree purely to be re-serialised as part of the response. Returning it as a
     * string would make clients parse JSON out of a JSON field.
     */
    private Object readGeoJson(String geoJson) {
        try {
            return objectMapper.readValue(geoJson, Map.class);
        } catch (JsonProcessingException cause) {
            throw new IllegalStateException("PostGIS returned geometry that is not valid GeoJSON", cause);
        }
    }

    private ResponseMeta staticMeta() {
        return ResponseMeta.forStatic(Instant.now(clock));
    }
}
