package ca.trackline.api.auth;

import java.time.Duration;

/**
 * Shared settings for the authentication tests: the shipped values, with the cheapest BCrypt and a fixed test secret.
 */
final class AuthFixtures {

    static final String SECRET = "test-only-secret-that-is-not-used-anywhere-else-0123456789";

    private AuthFixtures() {
    }

    static AuthProperties properties() {
        return new AuthProperties(
                SECRET, "trackline", Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(10),
                4, 10, 5, Duration.ofMinutes(15));
    }
}
