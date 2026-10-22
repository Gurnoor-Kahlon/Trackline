package ca.trackline.api.ratelimit;

import java.time.Duration;
import java.time.Instant;

import ca.trackline.api.ratelimit.RateLimitProperties.Limit;
import ca.trackline.api.ratelimit.TokenBuckets.Decision;
import ca.trackline.api.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenBucketsTest {

    private static final Limit THREE_THEN_SIX_A_MINUTE = new Limit(3, 6);

    private MutableClock clock;
    private TokenBuckets buckets;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        buckets = new TokenBuckets(clock, 100);
    }

    @Test
    void letsACallerSpendItsWholeBurstAtOnce() {
        for (int request = 1; request <= 3; request++) {
            assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).as("request %d", request).isTrue();
        }
    }

    @Test
    void refusesTheRequestAfterTheBurstAndSaysHowLongToWait() {
        for (int request = 0; request < 3; request++) {
            buckets.take("a", THREE_THEN_SIX_A_MINUTE);
        }

        Decision refused = buckets.take("a", THREE_THEN_SIX_A_MINUTE);

        assertThat(refused.allowed()).isFalse();
        // Six a minute is one every ten seconds, and the bucket is empty.
        assertThat(refused.retryAfter()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void refillsAtTheConfiguredRate() {
        for (int request = 0; request < 3; request++) {
            buckets.take("a", THREE_THEN_SIX_A_MINUTE);
        }

        clock.advance(Duration.ofSeconds(9));
        assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).as("9 s: not yet a whole token").isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).as("10 s: one token").isTrue();
        assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).as("and no more").isFalse();
    }

    @Test
    void neverHoldsMoreThanItsBurstHoweverLongTheCallerWaits() {
        clock.advance(Duration.ofHours(6));

        for (int request = 1; request <= 3; request++) {
            assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).isTrue();
        }
        assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).isFalse();
    }

    @Test
    void keepsOneCallersRequestsFromCountingAgainstAnother() {
        for (int request = 0; request < 3; request++) {
            buckets.take("a", THREE_THEN_SIX_A_MINUTE);
        }

        assertThat(buckets.take("a", THREE_THEN_SIX_A_MINUTE).allowed()).isFalse();
        assertThat(buckets.take("b", THREE_THEN_SIX_A_MINUTE).allowed()).isTrue();
    }

    @Test
    void forgetsCallersWhoHaveFullyRefilledWhenItNeedsRoom() {
        TokenBuckets small = new TokenBuckets(clock, 2);
        small.take("a", THREE_THEN_SIX_A_MINUTE);
        small.take("b", THREE_THEN_SIX_A_MINUTE);
        assertThat(small.tracked()).isEqualTo(2);

        // Long enough for both to be full again, which makes them indistinguishable from never having been seen.
        clock.advance(Duration.ofMinutes(5));
        small.take("c", THREE_THEN_SIX_A_MINUTE);

        assertThat(small.tracked()).isEqualTo(1);
    }

    @Test
    void doesNotForgetACallerWhoIsStillBeingLimited() {
        TokenBuckets small = new TokenBuckets(clock, 1);
        for (int request = 0; request < 3; request++) {
            small.take("heavy", THREE_THEN_SIX_A_MINUTE);
        }

        // The table is full of a caller who is not idle, so another caller is let through untracked...
        assertThat(small.take("newcomer", THREE_THEN_SIX_A_MINUTE).allowed()).isTrue();
        assertThat(small.tracked()).isEqualTo(1);
        // ...and the heavy caller is still limited, not reset by the sweep.
        assertThat(small.take("heavy", THREE_THEN_SIX_A_MINUTE).allowed()).isFalse();
    }

    @Test
    void judgesEachBucketAgainstItsOwnLimitWhenSweeping() {
        Limit large = new Limit(100, 60);
        TokenBuckets small = new TokenBuckets(clock, 2);
        // 50 of 100 spent: far from full, though a bucket of three would long since have refilled.
        for (int request = 0; request < 50; request++) {
            small.take("reader", large);
        }
        small.take("other", THREE_THEN_SIX_A_MINUTE);
        clock.advance(Duration.ofSeconds(30));

        small.take("third", THREE_THEN_SIX_A_MINUTE);

        // "other" had refilled and was dropped; "reader" had not and was kept.
        assertThat(small.take("reader", large).allowed()).isTrue();
        assertThat(small.tracked()).isEqualTo(2);
    }

    @Test
    void rejectsALimitThatCouldNeverLetAnythingThrough() {
        assertThatThrownBy(() -> new Limit(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limit(10, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
