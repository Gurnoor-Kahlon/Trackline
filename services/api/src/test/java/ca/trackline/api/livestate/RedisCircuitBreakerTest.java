package ca.trackline.api.livestate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import ca.trackline.api.livestate.RedisCircuitBreaker.OpenException;
import ca.trackline.api.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCircuitBreakerTest {

    private static final Duration OPEN_FOR = Duration.ofSeconds(10);

    private MutableClock clock;
    private RedisCircuitBreaker breaker;
    private AtomicInteger reachedRedis;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        breaker = new RedisCircuitBreaker(new LiveStateProperties(null, 3, OPEN_FOR), clock);
        reachedRedis = new AtomicInteger();
    }

    private String working() {
        return breaker.call(() -> {
            reachedRedis.incrementAndGet();
            return "answer";
        });
    }

    private void failing() {
        breaker.run(() -> {
            reachedRedis.incrementAndGet();
            throw new IllegalStateException("Redis timed out");
        });
    }

    private void failTimes(int times) {
        for (int attempt = 0; attempt < times; attempt++) {
            assertThatThrownBy(this::failing).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void passesCallsThroughWhileRedisIsWorking() {
        assertThat(working()).isEqualTo("answer");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void staysClosedBelowTheThresholdAndRethrowsTheRealFailure() {
        failTimes(2);

        assertThat(breaker.isOpen()).isFalse();
        assertThat(working()).isEqualTo("answer");
    }

    @Test
    void opensAfterTheThresholdOfConsecutiveFailures() {
        failTimes(3);

        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    void aSuccessInTheMiddleStartsTheCountAgain() {
        failTimes(2);
        working();
        failTimes(2);

        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void refusesCallsWithoutTouchingRedisWhileOpen() {
        failTimes(3);
        int before = reachedRedis.get();

        assertThatThrownBy(this::working).isInstanceOf(OpenException.class);
        assertThatThrownBy(this::failing).isInstanceOf(OpenException.class);

        assertThat(reachedRedis.get()).isEqualTo(before);
        assertThat(breaker.refusedCalls()).isEqualTo(2);
    }

    @Test
    void letsOneProbeThroughWhenThePauseEndsAndClosesIfItWorks() {
        failTimes(3);
        clock.advance(OPEN_FOR);

        assertThat(working()).isEqualTo("answer");

        assertThat(breaker.isOpen()).isFalse();
        assertThat(working()).isEqualTo("answer");
    }

    @Test
    void staysRefusingUntilThePauseHasFullyPassed() {
        failTimes(3);
        clock.advance(OPEN_FOR.minusMillis(1));

        assertThatThrownBy(this::working).isInstanceOf(OpenException.class);
    }

    @Test
    void aFailedProbeStartsTheWholePauseAgain() {
        failTimes(3);
        clock.advance(OPEN_FOR);
        assertThatThrownBy(this::failing).isInstanceOf(IllegalStateException.class);

        assertThat(breaker.isOpen()).isTrue();
        clock.advance(OPEN_FOR.minusMillis(1));
        assertThatThrownBy(this::working).isInstanceOf(OpenException.class);
        clock.advance(Duration.ofMillis(1));
        assertThat(working()).isEqualTo("answer");
    }

    @Test
    void aCallThatWasAlreadyInFlightWhenTheCircuitOpenedDoesNotExtendThePause() {
        failTimes(2);

        // Two calls are in flight together. One fails and opens the circuit; the other, admitted before it opened, fails after.
        assertThatThrownBy(() -> breaker.run(() -> {
            assertThatThrownBy(this::failing).isInstanceOf(IllegalStateException.class);
            assertThat(breaker.isOpen()).isTrue();
            clock.advance(Duration.ofSeconds(6));
            throw new IllegalStateException("the slow one failed too");
        })).isInstanceOf(IllegalStateException.class);

        // The pause is still the one that began when the circuit opened, six seconds ago, and not ten seconds from now.
        clock.advance(OPEN_FOR.minusSeconds(6));
        assertThat(working()).isEqualTo("answer");
    }

    @Test
    void refusesOtherCallersWhileOneProbeIsInFlight() {
        failTimes(3);
        clock.advance(OPEN_FOR);

        String outer = breaker.call(() -> {
            // While the probe is running, anyone else is turned away rather than also being sent to a Redis that may be down.
            assertThatThrownBy(this::working).isInstanceOf(OpenException.class);
            return "probe";
        });

        assertThat(outer).isEqualTo("probe");
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void aThresholdOfOneOpensOnTheFirstFailure() {
        RedisCircuitBreaker strict = new RedisCircuitBreaker(new LiveStateProperties(null, 1, OPEN_FOR), clock);

        assertThatThrownBy(() -> strict.run(() -> {
            throw new IllegalStateException("down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(strict.isOpen()).isTrue();
    }

    @Test
    void refusesSettingsThatCouldNeverWork() {
        assertThatThrownBy(() -> new LiveStateProperties(null, 0, OPEN_FOR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LiveStateProperties(null, 3, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
