package ca.trackline.api.analytics.history;

import java.time.Clock;
import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the hourly summary and the retention on their own schedules.
 *
 * <p>Neither is on the ingestion cycle's path: a slow summary or a drop waiting for a lock must not delay a
 * poll of the feed. A failure is logged and the next run tries again, since both are idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "trackline.history", name = "enabled", havingValue = "true")
public class HistoryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HistoryScheduler.class);

    private final HealthRollup rollup;
    private final HistoryRetention retention;
    private final Clock clock;
    private final MeterRegistry registry;

    public HistoryScheduler(HealthRollup rollup, HistoryRetention retention, Clock clock, MeterRegistry registry) {
        this.rollup = rollup;
        this.retention = retention;
        this.clock = clock;
        this.registry = registry;
    }

    @Scheduled(
            initialDelayString = "${trackline.history.startup-delay}",
            fixedDelayString = "${trackline.history.rollup-interval}")
    public void rollUp() {
        try {
            long started = System.nanoTime();
            int rows = rollup.rollUp(Instant.now(clock));
            log.info("History rollup wrote {} hourly rows in {} ms", rows, (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException failure) {
            registry.counter("trackline.history.job.failures", "job", "rollup").increment();
            log.warn("Could not summarise route health history: {}", failure.toString());
        }
    }

    @Scheduled(
            initialDelayString = "${trackline.history.startup-delay}",
            fixedDelayString = "${trackline.history.retention-interval}")
    public void trim() {
        try {
            retention.run(Instant.now(clock));
        } catch (RuntimeException failure) {
            registry.counter("trackline.history.job.failures", "job", "retention").increment();
            log.warn("Could not apply history retention: {}", failure.toString());
        }
    }
}
