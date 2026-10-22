package ca.trackline.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ca.trackline.api.ratelimit.RateLimitProperties.Limit;

/**
 * A token bucket per caller, kept in memory.
 *
 * <p>Each caller has a bucket that starts full, loses one token per request and gains tokens back at a steady rate up to its
 * capacity. An empty bucket refuses the request and says how long until a token is back. Tokens are counted as they are
 * asked for, from the time since the bucket was last touched, so there is no timer and an idle caller costs nothing but its entry.
 *
 * <p>Memory is bounded, because the key is whatever address a caller presents and the addresses cannot be chosen by the server. When
 * the table reaches its limit, entries that have refilled completely are dropped (a full bucket is the same as no bucket), and if
 * that does not make room a new caller is let through without being tracked. That fails open on purpose: it is a flood of distinct
 * addresses that fills the table, which is not something a per-address limit can stop, and refusing every new visitor to protect
 * the table would let that flood take the site down instead.
 *
 * <p>In memory, so each instance counts for itself and a restart forgets. With one instance polling the feeds and the rest idle, or a
 * load balancer spreading callers evenly, that is the right order of accuracy for a limit that exists to stop a runaway script.
 */
public final class TokenBuckets {

    /** The answer for one request. */
    public record Decision(boolean allowed, Duration retryAfter) {

        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    /** One caller's bucket for one kind of request, which is why it knows its own limit. */
    private static final class Bucket {

        private final Limit limit;
        private double tokens;
        private long lastMillis;

        Bucket(Limit limit, long nowMillis) {
            this.limit = limit;
            this.tokens = limit.burst();
            this.lastMillis = nowMillis;
        }

        synchronized Decision take(long nowMillis) {
            double perMilli = limit.perMinute() / 60_000.0;
            tokens = Math.min(limit.burst(), tokens + (nowMillis - lastMillis) * perMilli);
            lastMillis = nowMillis;
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.ALLOWED;
            }
            long waitMillis = (long) Math.ceil((1 - tokens) / perMilli);
            return new Decision(false, Duration.ofMillis(waitMillis));
        }

        synchronized boolean isFull(long nowMillis) {
            double perMilli = limit.perMinute() / 60_000.0;
            return tokens + (nowMillis - lastMillis) * perMilli >= limit.burst();
        }
    }

    private final Clock clock;
    private final int maxTracked;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBuckets(Clock clock, int maxTracked) {
        this.clock = clock;
        this.maxTracked = maxTracked;
    }

    /**
     * Spends one token from the caller's bucket.
     *
     * @param key   who and what, such as an address and the kind of request; the same key always means the same limit
     * @param limit the bucket's size and refill rate
     */
    public Decision take(String key, Limit limit) {
        long now = clock.millis();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxTracked) {
                buckets.values().removeIf(candidate -> candidate.isFull(now));
                if (buckets.size() >= maxTracked) {
                    return Decision.ALLOWED;
                }
            }
            bucket = buckets.computeIfAbsent(key, k -> new Bucket(limit, now));
        }
        return bucket.take(now);
    }

    /** How many callers are being tracked. For tests and diagnostics. */
    public int tracked() {
        return buckets.size();
    }
}
