package ca.trackline.api.gtfs.schedule;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the GTFS static import.
 *
 * <p>The download URL is deliberately absent: the archive is located at runtime
 * through the open-data catalogue's package API, so a republished resource does
 * not silently break the import.
 *
 * @param catalogueUrl     CKAN package endpoint describing the dataset
 * @param datasetId        dataset identifier within the catalogue
 * @param requestTimeout   per-request timeout for catalogue and download calls
 * @param maxArchiveBytes  refuse a download larger than this
 * @param maxEntryBytes    refuse a single archive entry larger than this
 * @param maxEntries       refuse an archive with more entries than this
 * @param copyBatchBytes   bytes buffered before flushing to the COPY stream
 * @param adminEndpoint    whether the manual import endpoint is exposed
 */
@Validated
@ConfigurationProperties(prefix = "trackline.gtfs.static")
public record GtfsStaticProperties(
        @NotBlank String catalogueUrl,
        @NotBlank String datasetId,
        Duration requestTimeout,
        @Positive long maxArchiveBytes,
        @Positive long maxEntryBytes,
        @Positive int maxEntries,
        @Positive int copyBatchBytes,
        boolean adminEndpoint) {

    public GtfsStaticProperties {
        if (requestTimeout == null) {
            requestTimeout = Duration.ofMinutes(5);
        }
    }
}
