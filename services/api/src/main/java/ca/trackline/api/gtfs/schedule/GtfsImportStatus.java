package ca.trackline.api.gtfs.schedule;

/**
 * Outcome of an import attempt.
 */
public enum GtfsImportStatus {

    /** The feed was downloaded and loaded; it is now the active network. */
    IMPORTED,

    /**
     * The published archive matched the active version byte for byte, so nothing
     * was reloaded. The common outcome, since the feed changes monthly.
     */
    UNCHANGED,

    /** Nothing was written and the previous network is still active. */
    FAILED
}
