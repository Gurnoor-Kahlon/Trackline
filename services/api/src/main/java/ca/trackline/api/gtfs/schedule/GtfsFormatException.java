package ca.trackline.api.gtfs.schedule;

/**
 * The feed did not parse. Thrown for a malformed field, a missing required file
 * or an archive that fails its safety limits.
 *
 * <p>Always aborts the import: a partially understood feed is not a network
 * Trackline can serve, so the previous one is kept instead.
 */
public class GtfsFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GtfsFormatException(String message) {
        super(message);
    }

    public GtfsFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
