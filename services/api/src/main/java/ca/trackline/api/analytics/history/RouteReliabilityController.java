package ca.trackline.api.analytics.history;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import ca.trackline.api.analytics.history.ReliabilityDtos.RouteReliabilityView;
import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;
import ca.trackline.api.analytics.history.ReliabilityModels.Reliability;
import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.InvalidRequestException;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.ReliabilityQueryRepository;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How steady a route's service has been over a past window.
 *
 * <p>Read from the hourly summaries, which are small and outlive the raw samples. Figures appear only when
 * enough history stands behind them; otherwise the answer says how much there is and how much is needed.
 * It is a successful answer with {@code INSUFFICIENT_HISTORY}, and not an error: the route exists, and
 * "not enough yet" is the true state of it, with counts the page can show.
 */
@RestController
@RequestMapping("/api/v1/routes")
@Tag(name = "Reliability", description = "How steady a route's service has been, from stored history")
public class RouteReliabilityController {

    /** The windows offered. A fixed set keeps requests cacheable and the windows comparable. */
    private static final Map<String, Integer> WINDOWS = Map.of("1d", 1, "7d", 7, "30d", 30, "90d", 90);

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final ReliabilityQueryRepository hourly;
    private final RouteQueryRepository routes;
    private final HistoryProperties properties;
    private final Clock clock;

    public RouteReliabilityController(
            ReliabilityQueryRepository hourly,
            RouteQueryRepository routes,
            HistoryProperties properties,
            Clock clock) {
        this.hourly = hourly;
        this.routes = routes;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping("/{routeId}/reliability")
    @Operation(
            summary = "How steady a route's service has been, with how much history stands behind it",
            description = "Over the last 1, 7, 30 or 90 days: the share of judged samples in which the route was "
                    + "excellent or normal, the mean headway variation, and the share with a bunch in it, overall and "
                    + "by hour of the Toronto day and by weekday. A sample is one route direction at one moment. "
                    + "Hours with too few samples are left out, and a breakdown bar is shown only with enough "
                    + "samples from enough different days. Below the minimum the status is INSUFFICIENT_HISTORY "
                    + "and no figures are returned, with what is needed and what there is.")
    public ApiEnvelope<RouteReliabilityView> reliability(
            @PathVariable String routeId, @RequestParam(defaultValue = "7d") String window) {
        Integer days = WINDOWS.get(window);
        if (days == null) {
            throw new InvalidRequestException(
                    "window must be one of %s".formatted(WINDOWS.keySet().stream().sorted().toList()));
        }
        if (!routes.exists(routeId)) {
            throw new ResourceNotFoundException("Route", routeId);
        }

        Instant now = Instant.now(clock);
        Instant from = now.minus(Duration.ofDays(days));
        List<HourlyRollup> rollups = hourly.findForRoute(routeId, from);
        Reliability result = new ReliabilityCalculator(properties.reliability(), TORONTO).calculate(rollups);

        return ApiEnvelope.of(
                RouteReliabilityView.of(routeId, result, days, from, TORONTO.getId(), properties.reliability()),
                ResponseMeta.stored(now));
    }
}
