package ca.trackline.api.common.error;

/**
 * A caller asked for a long-lived connection the server will not hold.
 *
 * <p>Each live stream occupies a queue and a delivery thread for as long as it is
 * open, so the number is capped overall and per client address. Its own type, mapped
 * to 429, so that being turned away reads as a limit to back off from rather than as
 * a server fault.
 */
public class TooManyConnectionsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TooManyConnectionsException(String message) {
        super(message);
    }
}
