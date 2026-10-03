package ca.trackline.api.ingestion;

import java.sql.Timestamp;
import java.time.Instant;

import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the record of each cycle.
 *
 * <p>In its own transaction, independent of whatever the cycle is doing. A failed
 * cycle still has to leave evidence that it ran and failed; if the record shared
 * the cycle's transaction it would be rolled back with it, and the only trace of a
 * failing feed would be a log line.
 */
@Component
public class IngestionRunRecorder {

    private final JdbcTemplate jdbc;

    public IngestionRunRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(
            RealtimeFeed feed,
            Instant startedAt,
            long durationMs,
            IngestionRunStatus status,
            int entityCount,
            int rejectedCount,
            Instant feedHeaderTimestamp,
            String errorSummary) {

        Long id = jdbc.queryForObject(
                """
                INSERT INTO ingestion_runs (
                    feed, started_at, duration_ms, status, entity_count,
                    rejected_count, feed_header_timestamp, error_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                feed.persistedName().name(),
                Timestamp.from(startedAt),
                durationMs,
                status.name(),
                entityCount,
                rejectedCount,
                feedHeaderTimestamp == null ? null : Timestamp.from(feedHeaderTimestamp),
                errorSummary);

        if (id == null) {
            throw new IllegalStateException("Recording an ingestion run returned no id");
        }
        return id;
    }

    /**
     * The row is written before the work so observations can reference it, which
     * means the duration has to be corrected once the work is done.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateDuration(long runId, long durationMs) {
        jdbc.update("UPDATE ingestion_runs SET duration_ms = ? WHERE id = ?", durationMs, runId);
    }
}
