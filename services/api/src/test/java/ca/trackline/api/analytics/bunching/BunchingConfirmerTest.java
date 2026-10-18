package ca.trackline.api.analytics.bunching;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingConfirmer.Confirmed;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A pack is held back until it has persisted, so one that is only passing never becomes an event.
 */
class BunchingConfirmerTest {

    private static final Instant T0 = Instant.parse("2026-10-13T14:00:00Z");

    private BunchingConfirmer confirmer;

    @BeforeEach
    void freshConfirmer() {
        confirmer = new BunchingConfirmer(BunchingFixtures.defaults());
    }

    private static BunchingCandidate pack(String route, int direction, String... ids) {
        return new BunchingCandidate(
                route, (short) direction, List.of(ids), 400, 3000, 6500, BunchingSeverity.MODERATE, SpacingBasis.SCHEDULE);
    }

    private List<Confirmed> sees(int cycle, BunchingCandidate... found) {
        return confirmer.update(List.of(found), candidate -> false, T0.plusSeconds(20L * cycle));
    }

    @Test
    void aPackSeenOnceIsNotYetRecorded() {
        assertThat(sees(0, pack("501", 0, "a", "b"))).isEmpty();
    }

    @Test
    void aPackSeenTwiceInARowIsStillHeldBack() {
        sees(0, pack("501", 0, "a", "b"));

        assertThat(sees(1, pack("501", 0, "a", "b"))).isEmpty();
    }

    @Test
    void aPackSeenOnThreeConsecutiveAnalysesIsConfirmedAndDatedFromItsFirstSighting() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b"));

        List<Confirmed> confirmed = sees(2, pack("501", 0, "a", "b"));

        assertThat(confirmed).singleElement().satisfies(run -> {
            assertThat(run.sightings()).isEqualTo(3);
            // The wait costs no time: it is dated from the first analysis, not the third.
            assertThat(run.firstSeenAt()).isEqualTo(T0);
        });
    }

    @Test
    void aPackKeepsBeingConfirmedForAsLongAsItPersists() {
        for (int cycle = 0; cycle < 2; cycle++) {
            sees(cycle, pack("501", 0, "a", "b"));
        }

        assertThat(sees(2, pack("501", 0, "a", "b"))).hasSize(1);
        assertThat(sees(3, pack("501", 0, "a", "b"))).singleElement().satisfies(run -> {
            assertThat(run.sightings()).isEqualTo(4);
            assertThat(run.firstSeenAt()).isEqualTo(T0);
        });
    }

    @Test
    void aPackThatVanishesForOneAnalysisStartsItsWaitAgain() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b"));
        sees(2);

        // Only consecutive sightings count, so two earlier ones are forgotten.
        assertThat(sees(3, pack("501", 0, "a", "b"))).isEmpty();
        assertThat(sees(4, pack("501", 0, "a", "b"))).isEmpty();
        assertThat(sees(5, pack("501", 0, "a", "b"))).singleElement()
                .satisfies(run -> assertThat(run.firstSeenAt()).isEqualTo(T0.plusSeconds(60)));
    }

    @Test
    void aPackThatPicksUpAVehicleIsTheSamePackAndKeepsCounting() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b", "c"));

        assertThat(sees(2, pack("501", 0, "a", "b", "c"))).singleElement()
                .satisfies(run -> assertThat(run.firstSeenAt()).isEqualTo(T0));
    }

    @Test
    void sharingOnlyOneVehicleIsADifferentPackWithItsOwnWait() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b"));

        assertThat(sees(2, pack("501", 0, "b", "c"))).isEmpty();
    }

    @Test
    void aPackThatContinuesAnOpenEventIsConfirmedAtOnce() {
        // A brief absence must not be able to end an event already recorded, so such a pack skips the wait.
        List<Confirmed> confirmed = confirmer.update(
                List.of(pack("501", 0, "a", "b")), candidate -> true, T0);

        assertThat(confirmed).singleElement().satisfies(run -> assertThat(run.sightings()).isEqualTo(1));
    }

    @Test
    void routesAndDirectionsAreWaitedOnSeparately() {
        sees(0, pack("501", 0, "a", "b"), pack("501", 1, "a", "b"), pack("504", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b"), pack("501", 1, "a", "b"), pack("504", 0, "a", "b"));

        // Only one of the three is seen a third time.
        assertThat(sees(2, pack("501", 1, "a", "b"))).singleElement()
                .satisfies(run -> assertThat(run.candidate().directionId()).isEqualTo((short) 1));
    }

    @Test
    void severalPacksOnOneDirectionAreEachWaitedOn() {
        for (int cycle = 0; cycle < 2; cycle++) {
            sees(cycle, pack("501", 0, "a", "b"), pack("501", 0, "c", "d"));
        }

        assertThat(sees(2, pack("501", 0, "a", "b"), pack("501", 0, "c", "d"))).hasSize(2);
    }

    @Test
    void nothingFoundConfirmsNothingAndForgetsWhatWasPending() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1);

        assertThat(sees(2, pack("501", 0, "a", "b"))).isEmpty();
    }

    @Test
    void forgettingStartsOver() {
        sees(0, pack("501", 0, "a", "b"));
        sees(1, pack("501", 0, "a", "b"));

        confirmer.forget();

        assertThat(sees(2, pack("501", 0, "a", "b"))).isEmpty();
    }
}
