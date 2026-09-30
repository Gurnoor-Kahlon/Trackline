package ca.trackline.api.gtfs.schedule;

/**
 * An archive the catalogue currently advertises.
 *
 * @param downloadUrl  where to fetch it
 * @param name         catalogue name for the resource, used in logs
 * @param lastModified catalogue timestamp as published, or null if absent
 */
public record GtfsFeedResource(String downloadUrl, String name, String lastModified) {
}
