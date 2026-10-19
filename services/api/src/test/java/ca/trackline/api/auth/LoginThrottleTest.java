package ca.trackline.api.auth;

import java.time.Duration;
import java.time.Instant;

import ca.trackline.api.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failed sign-ins are counted per address, and too many in a window shut that address for a while.
 */
class LoginThrottleTest {

    private static final Instant START = Instant.parse("2026-10-20T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final LoginThrottle throttle = new LoginThrottle(AuthFixtures.properties(), clock);

    private void fail(String address, int times) {
        for (int index = 0; index < times; index++) {
            throttle.recordFailure(address);
        }
    }

    @Test
    void fourFailuresAreAllowedForward() {
        fail("a@example.test", 4);

        assertThatCode(() -> throttle.requireAllowed("a@example.test")).doesNotThrowAnyException();
    }

    @Test
    void theFifthFailureShutsTheAddress() {
        fail("a@example.test", 5);

        assertThatThrownBy(() -> throttle.requireAllowed("a@example.test"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void anAddressNobodyHasFailedAgainstIsNeverShut() {
        assertThatCode(() -> throttle.requireAllowed("never@example.test")).doesNotThrowAnyException();
    }

    @Test
    void theWaitIsUntilTheOldestFailureAgesOut() {
        fail("a@example.test", 1);
        clock.advance(Duration.ofMinutes(5));
        fail("a@example.test", 4);
        clock.advance(Duration.ofMinutes(1));

        // The first failure was six minutes ago, in a fifteen-minute window: nine minutes to go.
        assertThatThrownBy(() -> throttle.requireAllowed("a@example.test"))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        refused -> assertThat(refused.retryAfter()).isEqualTo(Duration.ofMinutes(9)));
    }

    @Test
    void theAddressOpensWhenTheWindowHasPassed() {
        fail("a@example.test", 5);

        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> throttle.requireAllowed("a@example.test")).doesNotThrowAnyException();
    }

    @Test
    void justBeforeTheWindowPassesItIsStillShut() {
        fail("a@example.test", 5);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));

        assertThatThrownBy(() -> throttle.requireAllowed("a@example.test"))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void failuresThatHaveAgedOutNoLongerCount() {
        fail("a@example.test", 4);
        clock.advance(Duration.ofMinutes(16));
        fail("a@example.test", 4);

        // Eight failures in all, but only four are inside the window.
        assertThatCode(() -> throttle.requireAllowed("a@example.test")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulSignInClearsTheCount() {
        fail("a@example.test", 4);

        throttle.clear("a@example.test");
        fail("a@example.test", 4);

        assertThatCode(() -> throttle.requireAllowed("a@example.test")).doesNotThrowAnyException();
    }

    @Test
    void addressesAreCountedSeparately() {
        fail("a@example.test", 5);

        assertThatCode(() -> throttle.requireAllowed("b@example.test")).doesNotThrowAnyException();
    }

    @Test
    void theNumberOfAddressesRememberedIsBoundedSoUnregisteredOnesCannotFillMemory() {
        fail("victim@example.test", 5);

        for (int index = 0; index < LoginThrottle.MAX_TRACKED + 10; index++) {
            throttle.recordFailure("nobody-" + index + "@example.test");
        }

        // The oldest entry was dropped to make room. This is the stated cost of the bound.
        assertThatCode(() -> throttle.requireAllowed("victim@example.test")).doesNotThrowAnyException();
        // And the recent ones are still held.
        fail("nobody-" + (LoginThrottle.MAX_TRACKED + 9) + "@example.test", 4);
        assertThatThrownBy(() -> throttle.requireAllowed("nobody-" + (LoginThrottle.MAX_TRACKED + 9) + "@example.test"))
                .isInstanceOf(TooManyAttemptsException.class);
    }
}
