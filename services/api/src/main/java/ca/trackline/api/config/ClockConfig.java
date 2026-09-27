package ca.trackline.api.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application clock.
 *
 * <p>Everything that needs the current time injects {@link Clock} rather than
 * calling {@code Instant.now()}, which is what lets freshness, staleness and
 * later the analytics algorithms be tested deterministically with a fixed clock.
 * UTC is used throughout; local Toronto time is a presentation concern.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
