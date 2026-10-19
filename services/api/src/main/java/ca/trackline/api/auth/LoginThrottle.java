package ca.trackline.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Slows down someone guessing an account's password.
 *
 * <p>After a set number of failed sign-ins for one address inside a window, further attempts for it are refused until the
 * oldest failure has aged out. A success clears the count. It counts failures for an address whether or not an account
 * exists, so the refusal itself does not reveal which addresses are registered.
 *
 * <p>It is kept by address and not by caller. Behind the web application every caller arrives from the same place, so a
 * limit per source would throttle everyone for one person's guessing. The price is that someone can lock a named
 * account for a window by failing against it deliberately; the account is not lost, and it opens again when the window
 * passes. Limiting by source belongs in front of the application, where the real source is known.
 *
 * <p>In memory, so it forgets on restart and does not span instances, and bounded, so it cannot be made to hold
 * without limit by sending addresses that were never registered. When the bound is reached the oldest entry is
 * dropped, which lets a flood of other addresses reset a count: it slows guessing, and does not stop a determined
 * attacker, and docs/authentication.md says so.
 */
@Component
public class LoginThrottle {

    /** More distinct addresses than this are not tracked at once. */
    static final int MAX_TRACKED = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public LoginThrottle(AuthProperties properties, Clock clock) {
        this.maxFailures = properties.maxFailedLogins();
        this.window = properties.failedLoginWindow();
        this.clock = clock;
    }

    /** Throws if this address has failed too often too recently. Call before checking the password. */
    public synchronized void requireAllowed(String address) {
        Deque<Instant> recent = recent(address);
        if (recent != null && recent.size() >= maxFailures) {
            Instant lifted = recent.peekFirst().plus(window);
            throw new TooManyAttemptsException(Duration.between(Instant.now(clock), lifted));
        }
    }

    public synchronized void recordFailure(String address) {
        Deque<Instant> recent = recent(address);
        if (recent == null) {
            recent = new ArrayDeque<>();
            failures.put(address, recent);
        }
        recent.addLast(Instant.now(clock));
    }

    public synchronized void clear(String address) {
        failures.remove(address);
    }

    /** The failures still inside the window, dropping those that have aged out. Null when there are none. */
    private Deque<Instant> recent(String address) {
        Deque<Instant> recent = failures.get(address);
        if (recent == null) {
            return null;
        }
        Instant cutoff = Instant.now(clock).minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
        if (recent.isEmpty()) {
            failures.remove(address);
            return null;
        }
        return recent;
    }
}
