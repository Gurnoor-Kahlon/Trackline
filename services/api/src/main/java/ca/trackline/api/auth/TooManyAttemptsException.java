package ca.trackline.api.auth;

import java.time.Duration;

/**
 * Too many failed sign-ins for one account. Carries how long to wait, so the response can say.
 */
public class TooManyAttemptsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super("Too many failed sign-in attempts. Try again later.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
