package ca.trackline.api.common.error;

/**
 * A request the caller got wrong in a way annotations cannot express.
 *
 * <p>Deliberately its own type rather than reusing {@link IllegalArgumentException}:
 * that is also thrown by ordinary bugs, and mapping it to 400 would report
 * Trackline's mistakes as the caller's.
 */
public class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestException(String message) {
        super(message);
    }
}
