package ca.trackline.api.persistence.entity;

/**
 * Lifecycle of a GTFS static import. Exactly one version may be {@link #ACTIVE},
 * enforced by a partial unique index rather than by import code.
 */
public enum FeedVersionStatus {

    /** Rows are being written; this version is not yet servable. */
    IMPORTING,

    /** The live network. */
    ACTIVE,

    /** The import did not complete; its rows were rolled back. */
    FAILED,

    /** Replaced by a newer successful import, retained for audit. */
    SUPERSEDED
}
