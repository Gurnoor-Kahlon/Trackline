package ca.trackline.api.ratelimit;

import java.time.Duration;

/**
 * A caller has made more requests than it is allowed. Carries how long until one will be let through, so the answer can say.
 */
public class RateLimitedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super("Too many requests from this address. Try again shortly.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
