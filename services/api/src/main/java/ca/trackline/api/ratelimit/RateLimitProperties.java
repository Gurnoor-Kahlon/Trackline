package ca.trackline.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How many requests one address may make.
 *
 * @param enabled        whether requests are limited at all
 * @param reads          the budget for reading transit data
 * @param search         the budget for search, the one read that costs a trigram query across three tables
 * @param writes         the budget for changing something (saving or removing a favourite)
 * @param maxTracked     how many addresses are tracked at once; past this, new ones go unlimited rather than make memory grow
 */
@Validated
@ConfigurationProperties(prefix = "trackline.rate-limit")
public record RateLimitProperties(Boolean enabled, Limit reads, Limit search, Limit writes, Integer maxTracked) {

    /**
     * A token bucket: it holds up to {@code burst} requests, and is refilled at {@code perMinute}.
     *
     * <p>So a caller may spend {@code burst} requests at once and then {@code perMinute} a minute for as long as it likes. The
     * burst is what lets a page that makes a dozen requests as it loads go through untouched, and the rate is what stops a script.
     */
    public record Limit(int burst, int perMinute) {

        public Limit {
            if (burst < 1 || perMinute < 1) {
                throw new IllegalArgumentException("a rate limit needs a burst and a rate of at least 1");
            }
        }
    }

    public RateLimitProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (reads == null) {
            reads = new Limit(120, 120);
        }
        if (search == null) {
            search = new Limit(20, 30);
        }
        if (writes == null) {
            writes = new Limit(30, 30);
        }
        if (maxTracked == null) {
            maxTracked = 20_000;
        }
        if (maxTracked < 1) {
            throw new IllegalArgumentException("trackline.rate-limit.max-tracked must be at least 1");
        }
    }
}
