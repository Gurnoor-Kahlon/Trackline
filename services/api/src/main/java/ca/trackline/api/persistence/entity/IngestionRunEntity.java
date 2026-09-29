package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The record of one ingestion cycle: how long it took, how much it carried and
 * what went wrong. Every performance figure Trackline publishes traces to here.
 */
@Entity
@Table(name = "ingestion_runs")
public class IngestionRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "feed", nullable = false)
    private IngestionFeed feed;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IngestionRunStatus status;

    @Column(name = "entity_count", nullable = false)
    private int entityCount;

    /** Entities dropped by validation while the cycle still succeeded. */
    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    /** The publisher's own timestamp, used to detect a payload that has not changed. */
    @Column(name = "feed_header_timestamp")
    private Instant feedHeaderTimestamp;

    /** Short cause for a failed cycle. Never a stack trace. */
    @Column(name = "error_summary")
    private String errorSummary;

    protected IngestionRunEntity() {
        // Required by JPA.
    }

    public IngestionRunEntity(IngestionFeed feed, Instant startedAt, int durationMs, IngestionRunStatus status, int entityCount) {
        this.feed = feed;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.status = status;
        this.entityCount = entityCount;
    }

    public Long getId() {
        return id;
    }

    public IngestionFeed getFeed() {
        return feed;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getDurationMs() {
        return durationMs;
    }

    public IngestionRunStatus getStatus() {
        return status;
    }

    public int getEntityCount() {
        return entityCount;
    }

    public int getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(int rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public Instant getFeedHeaderTimestamp() {
        return feedHeaderTimestamp;
    }

    public void setFeedHeaderTimestamp(Instant feedHeaderTimestamp) {
        this.feedHeaderTimestamp = feedHeaderTimestamp;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

}
