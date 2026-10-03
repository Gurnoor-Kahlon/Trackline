package ca.trackline.api.ingestion;

import java.util.concurrent.TimeUnit;

import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Cycle counters and timers.
 *
 * <p>Tagged by feed and outcome so the question that actually matters — is any one
 * feed failing, and for how long — is answerable without reading logs. The database
 * holds the same facts for history; this is for watching them live.
 */
@Component
public class IngestionMetrics {

    private static final String CYCLE_TIMER = "trackline.ingestion.cycle";
    private static final String ENTITIES_ACCEPTED = "trackline.ingestion.entities.accepted";
    private static final String ENTITIES_REJECTED = "trackline.ingestion.entities.rejected";
    private static final String OBSERVATIONS_STORED = "trackline.ingestion.observations.stored";
    private static final String CYCLES_SKIPPED = "trackline.ingestion.cycles.skipped";

    private final MeterRegistry registry;

    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordCycle(RealtimeFeed feed, IngestionRunStatus status, long durationMs) {
        registry.timer(CYCLE_TIMER, "feed", feed.name(), "status", status.name())
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordEntities(RealtimeFeed feed, int accepted, int rejected, int stored) {
        registry.counter(ENTITIES_ACCEPTED, "feed", feed.name()).increment(accepted);
        registry.counter(ENTITIES_REJECTED, "feed", feed.name()).increment(rejected);
        registry.counter(OBSERVATIONS_STORED, "feed", feed.name()).increment(stored);
    }

    /** A skipped cycle means the previous one was still running, which is worth watching. */
    public void recordSkipped(RealtimeFeed feed) {
        registry.counter(CYCLES_SKIPPED, "feed", feed.name()).increment();
    }
}
