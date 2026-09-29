package ca.trackline.api.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One GTFS static import. The {@code sha256} of the downloaded archive makes
 * re-importing an unchanged feed a no-op, and {@link #feedStartDate}/
 * {@link #feedEndDate} record the service period the feed actually describes —
 * which is not necessarily the current date.
 */
@Entity
@Table(name = "gtfs_feed_versions")
public class GtfsFeedVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "downloaded_at", nullable = false)
    private Instant downloadedAt;

    @Column(name = "imported_at")
    private Instant importedAt;

    @Column(name = "feed_start_date")
    private LocalDate feedStartDate;

    @Column(name = "feed_end_date")
    private LocalDate feedEndDate;

    /** Per-file row counts from the import, kept for auditing what was loaded. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "row_counts")
    private Map<String, Integer> rowCounts;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FeedVersionStatus status;

    protected GtfsFeedVersionEntity() {
        // Required by JPA.
    }

    public GtfsFeedVersionEntity(String sha256, String sourceUrl, Instant downloadedAt, FeedVersionStatus status) {
        this.sha256 = sha256;
        this.sourceUrl = sourceUrl;
        this.downloadedAt = downloadedAt;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getSha256() {
        return sha256;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public Instant getDownloadedAt() {
        return downloadedAt;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Instant importedAt) {
        this.importedAt = importedAt;
    }

    public LocalDate getFeedStartDate() {
        return feedStartDate;
    }

    public void setFeedStartDate(LocalDate feedStartDate) {
        this.feedStartDate = feedStartDate;
    }

    public LocalDate getFeedEndDate() {
        return feedEndDate;
    }

    public void setFeedEndDate(LocalDate feedEndDate) {
        this.feedEndDate = feedEndDate;
    }

    public Map<String, Integer> getRowCounts() {
        return rowCounts;
    }

    public void setRowCounts(Map<String, Integer> rowCounts) {
        this.rowCounts = rowCounts;
    }

    public FeedVersionStatus getStatus() {
        return status;
    }

    public void setStatus(FeedVersionStatus status) {
        this.status = status;
    }

    /**
     * Whether this feed's service period covers {@code date}. Schedule-derived
     * features check this instead of assuming the active feed describes today.
     */
    public boolean covers(LocalDate date) {
        return feedStartDate != null
                && feedEndDate != null
                && !date.isBefore(feedStartDate)
                && !date.isAfter(feedEndDate);
    }
}
