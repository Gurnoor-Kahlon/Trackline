package ca.trackline.api.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.alerts.AlertDtos.AlertView;
import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.common.envelope.Page;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.persistence.query.AlertQueryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service alerts, with the lifecycle Trackline observed for them.
 *
 * <p>The feed gives no start, end or severity, so what is served is when Trackline
 * first and last saw each alert in it. The envelope's {@code feedTimestamp} says how
 * current that picture is.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Validated
@Tag(name = "Alerts", description = "Service alerts and their observed lifecycle")
public class AlertController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Age past which stored alerts are called stale. The alerts feed is polled every
     * minute and the publisher updates about every twenty seconds, so five minutes is
     * several missed polls, not ordinary lag. The vehicle threshold of one minute would
     * flag healthy alerts as stale much of the time.
     */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final String ID_FORMAT = "^[A-Za-z0-9_.-]{1,32}$";

    private final AlertQueryRepository alerts;
    private final Clock clock;

    public AlertController(AlertQueryRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @GetMapping
    @Operation(
            summary = "Alerts, newest first",
            description = "By default only alerts still in the feed. With `active=false`, only those that have "
                    + "left it. `route` and `stop` keep alerts naming that route or stop. The feed publishes "
                    + "no active period, so times are when Trackline first and last saw an alert. An empty "
                    + "list with `meta.source` NO_DATA means alerts have never been ingested, not that "
                    + "there are none.")
    public ApiEnvelope<Page<AlertView>> list(
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(required = false) @Pattern(regexp = ID_FORMAT) String route,
            @RequestParam(required = false) @Pattern(regexp = ID_FORMAT) String stop,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        long total = alerts.count(active, route, stop);
        List<AlertView> items = total == 0 ? List.of() : alerts.find(active, route, stop, page, size);
        return ApiEnvelope.of(new Page<>(items, page, size, total), meta());
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "One alert, active or resolved")
    public ApiEnvelope<AlertView> detail(@PathVariable long alertId) {
        AlertView alert = alerts.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", String.valueOf(alertId)));
        return ApiEnvelope.of(alert, meta());
    }

    private ResponseMeta meta() {
        Instant now = Instant.now(clock);
        Optional<Instant> feed = alerts.lastAppliedFeedTimestamp();
        if (feed.isEmpty()) {
            return ResponseMeta.noData(now);
        }
        long age = Math.max(0, Duration.between(feed.get(), now).toSeconds());
        return new ResponseMeta(now, feed.get(), age, age > STALE_AFTER.toSeconds(), false, DataSource.STORED);
    }
}
