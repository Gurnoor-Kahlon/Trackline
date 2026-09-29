package ca.trackline.api.persistence.entity;

/**
 * Outcome of one ingestion cycle.
 */
public enum IngestionRunStatus {

    /** Fetched, parsed and applied. */
    SUCCESS,

    /** The feed header timestamp had not advanced, so no work was repeated. */
    NO_CHANGE,

    /**
     * Applied, but the payload looked wrong — too many rejected entities or an
     * implausible drop in entity count. Surfaced to clients as degraded rather
     * than trusted silently.
     */
    SUSPECT,

    /** Fetch or parse failed; the previous snapshot was retained. */
    FAILED
}
