package ca.trackline.api.livestate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the live-state cache.
 *
 * @param snapshotTtl how long a written snapshot stays readable
 */
@Validated
@ConfigurationProperties(prefix = "trackline.live-state")
public record LiveStateProperties(Duration snapshotTtl) {

    public LiveStateProperties {
        if (snapshotTtl == null) {
            snapshotTtl = Duration.ofMinutes(2);
        }
    }
}
