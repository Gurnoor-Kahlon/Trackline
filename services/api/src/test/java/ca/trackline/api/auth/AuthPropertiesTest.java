package ca.trackline.api.auth;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Settings that would weaken sign-in are refused at startup, and the secret never prints.
 */
class AuthPropertiesTest {

    private static AuthProperties with(String secret, Duration access, Duration refresh, int cost, int minLength) {
        return new AuthProperties(
                secret, "trackline", access, refresh, Duration.ofSeconds(10), cost, minLength, 5, Duration.ofMinutes(15));
    }

    private static AuthProperties good() {
        return AuthFixtures.properties();
    }

    @Test
    void aSecretOfThirtyTwoBytesIsEnoughAndThirtyOneIsNot() {
        assertThat(with("x".repeat(32), Duration.ofMinutes(15), Duration.ofDays(30), 12, 10)).isNotNull();
        assertThatThrownBy(() -> with("x".repeat(31), Duration.ofMinutes(15), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void theLengthOfTheSecretIsInBytesNotCharacters() {
        // Sixteen two-byte characters are thirty-two bytes.
        assertThat(with("é".repeat(16), Duration.ofMinutes(15), Duration.ofDays(30), 12, 10)).isNotNull();
        assertThatThrownBy(() -> with("é".repeat(15), Duration.ofMinutes(15), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingSecretStopsStartup() {
        assertThatThrownBy(() -> with(null, Duration.ofMinutes(15), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("", Duration.ofMinutes(15), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnresolvedPlaceholderIsNotMistakenForASecret() {
        assertThatThrownBy(() -> with("${TRACKLINE_AUTH_JWT_SECRET}", Duration.ofMinutes(15), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAccessTokenMustExpireBeforeTheRefreshTokenThatRenewsIt() {
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofDays(30), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofDays(31), Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLifetimeOfZeroOrLessIsRefused() {
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ZERO, Duration.ofDays(30), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofSeconds(-1), 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBcryptCostMustBeBetweenFourAndSixteen() {
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 3, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 17, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 4, 10)).isNotNull();
        assertThat(with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 16, 10)).isNotNull();
    }

    @Test
    void theMinimumPasswordLengthCannotBeBelowEightOrAboveWhatBcryptReads() {
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 12, 7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(AuthFixtures.SECRET, Duration.ofMinutes(15), Duration.ofDays(30), 12, 73))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSecretDoesNotAppearInThePrintedSettings() {
        assertThat(good().toString()).doesNotContain(AuthFixtures.SECRET).contains("issuer=trackline");
    }
}
