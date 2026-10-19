package ca.trackline.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How sign-in works: the secret that signs access tokens, how long tokens last, and how hard a guessed
 * password is made to be.
 *
 * <p>The secret has no default, as the database password has none: an environment missing it must stop at startup,
 * not fall back to a value anyone reading this repository knows. Every token ever issued would be forgeable with it.
 * It is also kept out of {@link #toString()}, so it cannot reach a log through a configuration dump.
 *
 * @param jwtSecret           the key access tokens are signed with, at least 32 bytes; HMAC-SHA256 is only as strong as it is
 * @param issuer              the {@code iss} claim a token must carry, so a token minted for something else is refused
 * @param accessTokenTtl      how long an access token is good for. Short, since it cannot be taken back once issued
 * @param refreshTokenTtl     how long a sign-in lasts without being used
 * @param refreshReuseLeeway  a refresh token exchanged this recently may be presented again without ending the family. Two
 *                            tabs refreshing together send the same token, and treating the second as theft would sign the
 *                            person out for opening a second tab
 * @param bcryptCost          BCrypt's work factor. Each step doubles the time to check one guess, for the attacker and the
 *                            server alike
 * @param minPasswordLength   shortest password accepted, in characters. Length is the rule: composition rules make people
 *                            predictable and do not make passwords hard to guess
 * @param maxFailedLogins     failures for one account and address within the window before further attempts are refused
 * @param failedLoginWindow   how long failures are remembered, and how long an attempt is refused for once the limit is reached
 */
@Validated
@ConfigurationProperties(prefix = "trackline.auth")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration refreshReuseLeeway,
        int bcryptCost,
        int minPasswordLength,
        int maxFailedLogins,
        @NotNull Duration failedLoginWindow) {

    /** BCrypt ignores everything past this many bytes, so a longer password would be silently shortened. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** HMAC-SHA256 needs a key as long as its output to be as strong as it claims. */
    static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "trackline.auth.jwt-secret must be set to at least %d bytes".formatted(MIN_SECRET_BYTES));
        }
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
        requirePositive(failedLoginWindow, "failed-login-window");
        if (refreshReuseLeeway == null || refreshReuseLeeway.isNegative()) {
            throw new IllegalArgumentException("trackline.auth.refresh-reuse-leeway cannot be negative");
        }
        if (accessTokenTtl.compareTo(refreshTokenTtl) >= 0) {
            throw new IllegalArgumentException("The access token must expire before the refresh token that renews it");
        }
        // Below 4 BCrypt refuses; above 16 one sign-in takes tens of seconds.
        if (bcryptCost < 4 || bcryptCost > 16) {
            throw new IllegalArgumentException("trackline.auth.bcrypt-cost must be between 4 and 16");
        }
        if (minPasswordLength < 8 || minPasswordLength > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException(
                    "trackline.auth.min-password-length must be between 8 and %d".formatted(MAX_PASSWORD_BYTES));
        }
        if (maxFailedLogins < 1) {
            throw new IllegalArgumentException("trackline.auth.max-failed-logins must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("trackline.auth." + name + " must be positive");
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl
                + ", refreshTokenTtl=" + refreshTokenTtl + ", bcryptCost=" + bcryptCost + "]";
    }
}
