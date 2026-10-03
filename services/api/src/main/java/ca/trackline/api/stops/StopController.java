package ca.trackline.api.stops;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.StopQueryRepository;
import ca.trackline.api.stops.StopDtos.NearbyStop;
import ca.trackline.api.stops.StopDtos.StopDetail;
import ca.trackline.api.stops.StopDtos.StopSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stops, including the nearby search.
 */
@RestController
@RequestMapping("/api/v1/stops")
@Validated
@Tag(name = "Stops", description = "Stops, platforms and proximity search")
public class StopController {

    /** A radius beyond this stops being "nearby" and starts being a table scan. */
    private static final int MAX_RADIUS_METRES = 5_000;
    private static final int DEFAULT_RADIUS_METRES = 500;
    private static final int MAX_NEARBY = 100;
    private static final int DEFAULT_NEARBY = 20;

    private final StopQueryRepository stops;
    private final Clock clock;

    public StopController(StopQueryRepository stops, Clock clock) {
        this.stops = stops;
        this.clock = clock;
    }

    @GetMapping("/nearby")
    @Operation(
            summary = "Stops near a point, nearest first",
            description = "Distances are metres on the spheroid. Coordinates are used for this query "
                    + "only and are not logged.")
    public ApiEnvelope<List<NearbyStop>> nearby(
            // Bounded to plausible coordinates: a request outside these is a client
            // bug or a probe, and either way should fail rather than scan.
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lon,
            @RequestParam(defaultValue = "" + DEFAULT_RADIUS_METRES)
                    @Min(1) @Max(MAX_RADIUS_METRES) int radiusMetres,
            @RequestParam(defaultValue = "" + DEFAULT_NEARBY) @Min(1) @Max(MAX_NEARBY) int limit) {

        return ApiEnvelope.of(stops.findNearby(lat, lon, radiusMetres, limit), staticMeta());
    }

    @GetMapping("/{stopId}")
    @Operation(summary = "A stop and the routes that call there")
    public ApiEnvelope<StopDetail> detail(@PathVariable String stopId) {
        StopSummary stop = stops.findStop(stopId)
                .orElseThrow(() -> new ResourceNotFoundException("Stop", stopId));

        return ApiEnvelope.of(new StopDetail(stop, stops.findRoutesServing(stopId)), staticMeta());
    }

    private ResponseMeta staticMeta() {
        return ResponseMeta.forStatic(Instant.now(clock));
    }
}
