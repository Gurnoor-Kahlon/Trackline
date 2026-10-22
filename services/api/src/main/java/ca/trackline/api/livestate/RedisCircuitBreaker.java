package ca.trackline.api.livestate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stops asking Redis while Redis is not answering.
 *
 * <p>The API already survives Redis being down: a read that fails falls through to this instance's own copy and then to the
 * database, and says so. What it did not avoid was the price of finding out. Every call waited out Redis's 500 ms timeout before
 * giving up, so while Redis was down every live read was about ten times slower, and the ingestion cycle paid the same for each
 * of its writes. Measured on 2026-10-03: 0.54 to 0.56 s per request, against about 0.05 s with Redis up.
 *
 * <p>After a run of consecutive failures the breaker opens, and for a while every call fails at once without touching Redis, so a
 * caller reaches its fallback in microseconds. When the pause ends a single call is let through as a probe. If it succeeds the
 * breaker closes and everything returns to Redis; if it fails the pause starts again. Concurrent callers during a probe are
 * refused like the rest, so a recovering Redis is met by one request and not by a crowd.
 *
 * <p>One breaker for all of the API's Redis use: the vehicle snapshot and the route health documents are one server, and a server
 * that has stopped answering one has stopped answering the other.
 *
 * <p>It protects latency and says nothing about correctness: a refused call is handled exactly like a failed one.
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    /** What a caller sees when the breaker refuses a call without trying it. */
    public static final class OpenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OpenException() {
            super("Redis is not being tried: the circuit is open", null, false, false);
        }
    }

    private enum State {
        CLOSED,
        OPEN,
        PROBING
    }

    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant reopensAt = Instant.MIN;
    private long refused;

    public RedisCircuitBreaker(LiveStateProperties properties, Clock clock) {
        this.failureThreshold = properties.breakerFailureThreshold();
        this.openFor = properties.breakerOpenFor();
        this.clock = clock;
    }

    /**
     * Runs a Redis call, or refuses it at once.
     *
     * @throws OpenException when the circuit is open, without having called Redis
     */
    public <T> T call(Supplier<T> operation) {
        admit();
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException failure) {
            recordFailure(failure);
            throw failure;
        }
        recordSuccess();
        return result;
    }

    /** As {@link #call(Supplier)}, for a call with nothing to return. */
    public void run(Runnable operation) {
        call(() -> {
            operation.run();
            return null;
        });
    }

    /** Whether calls are currently being refused. For tests and diagnostics. */
    public synchronized boolean isOpen() {
        return state != State.CLOSED;
    }

    /** How many calls have been refused without trying Redis since this process started. */
    public synchronized long refusedCalls() {
        return refused;
    }

    private synchronized void admit() {
        switch (state) {
            case CLOSED -> {
                return;
            }
            case OPEN -> {
                if (clock.instant().isBefore(reopensAt)) {
                    refused++;
                    throw new OpenException();
                }
                state = State.PROBING;
                log.info("Trying Redis again, with one call");
            }
            case PROBING -> {
                refused++;
                throw new OpenException();
            }
        }
    }

    private synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("Redis answered again; using it");
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
    }

    private synchronized void recordFailure(RuntimeException failure) {
        if (state == State.OPEN) {
            // A call admitted just before the circuit opened, failing after it. The pause is already running, and counting this as a
            // fresh failure would log the same outage twice and push the end of the pause further out.
            return;
        }
        consecutiveFailures++;
        if (state == State.PROBING || consecutiveFailures >= failureThreshold) {
            if (state != State.PROBING) {
                log.warn(
                        "Redis failed {} times running; not trying it for {} ({})",
                        consecutiveFailures, openFor, failure.toString());
            }
            state = State.OPEN;
            reopensAt = clock.instant().plus(openFor);
        }
    }
}
