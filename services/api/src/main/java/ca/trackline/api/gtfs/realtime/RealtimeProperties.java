package ca.trackline.api.gtfs.realtime;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for realtime ingestion.
 *
 * <p>The poll intervals are set from measurement, not preference — see the comment
 * on the configured values. Polling faster than the publisher updates buys nothing
 * and costs bandwidth on every cycle.
 *
 * @param baseUrl              publisher base, feed paths are appended
 * @param connectTimeout       give up quickly on an unreachable host
 * @param readTimeout          a feed that stalls must not hold a cycle open
 * @param vehicleInterval      how often to poll vehicle positions
 * @param tripUpdateInterval   how often to poll trip updates
 * @param alertInterval        how often to poll alerts
 * @param maxResponseBytes     refuse a response larger than this
 * @param suspectRejectionRate above this share of dropped entities, a cycle is suspect
 * @param suspectDropRatio     a drop in entity count beyond this marks a cycle suspect
 * @param minObservationGap    minimum time between stored observations of a stationary vehicle
 * @param minObservationMetres movement that always earns a stored observation
 * @param enabled              whether the scheduled pollers run
 */
@Validated
@ConfigurationProperties(prefix = "trackline.gtfs.realtime")
public record RealtimeProperties(
        @NotBlank String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration vehicleInterval,
        Duration tripUpdateInterval,
        Duration alertInterval,
        @Positive long maxResponseBytes,
        double suspectRejectionRate,
        double suspectDropRatio,
        Duration minObservationGap,
        @Positive double minObservationMetres,
        boolean enabled) {

    public String urlFor(RealtimeFeed feed) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/" + feed.path();
    }
}
