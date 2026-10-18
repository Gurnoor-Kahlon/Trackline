package ca.trackline.api.analytics.bunching;

import java.util.List;
import java.util.Set;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingTracker.Continued;
import ca.trackline.api.analytics.bunching.BunchingTracker.Missed;
import ca.trackline.api.analytics.bunching.BunchingTracker.Open;
import ca.trackline.api.analytics.bunching.BunchingTracker.Plan;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case of a bunch's life: first seen, still there, changed, gone, gone for good.
 */
class BunchingTrackerTest {

    private final BunchingTracker tracker = new BunchingTracker(BunchingFixtures.defaults());

    private static BunchingCandidate found(String route, int direction, String... ids) {
        return new BunchingCandidate(
                route, (short) direction, List.of(ids), 400, 3000, 6500, BunchingSeverity.MODERATE, SpacingBasis.SCHEDULE);
    }

    private static Open open(long id, String route, int direction, int missed, String... ids) {
        return new Open(id, route, (short) direction, Set.of(ids), missed);
    }

    @Test
    void aBunchWithNoOpenPredecessorIsOpened() {
        BunchingCandidate fresh = found("501", 0, "a", "b");

        Plan plan = tracker.reconcile(List.of(), List.of(fresh));

        assertThat(plan.toOpen()).containsExactly(fresh);
        assertThat(plan.continued()).isEmpty();
        assertThat(plan.missed()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void theSameVehiclesSeenAgainAreTheSameEvent() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 0, "a", "b")), List.of(found("501", 0, "a", "b")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(7L);
        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.missed()).isEmpty();
    }

    @Test
    void aBunchThatPicksUpAThirdVehicleIsStillTheSameEvent() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 0, "a", "b")), List.of(found("501", 0, "a", "b", "c")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(7L);
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void aBunchThatLosesAVehicleIsStillTheSameEventWhileTwoRemain() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 0, "a", "b", "c")), List.of(found("501", 0, "b", "c")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(7L);
    }

    @Test
    void sharingOnlyOneVehicleIsADifferentBunch() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 0, "a", "b")), List.of(found("501", 0, "b", "c")));

        assertThat(plan.toOpen()).hasSize(1);
        assertThat(plan.continued()).isEmpty();
        // The old one is simply not seen this time.
        assertThat(plan.missed()).containsExactly(new Missed(7, 1));
    }

    @Test
    void aBunchNotSeenOnceIsMissedAndNotYetResolved() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 0, "a", "b")), List.of());

        assertThat(plan.missed()).containsExactly(new Missed(7, 1));
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void aBunchNotSeenTwiceInARowIsResolved() {
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 1, "a", "b")), List.of());

        assertThat(plan.toResolve()).containsExactly(7L);
        assertThat(plan.missed()).isEmpty();
    }

    @Test
    void aBunchThatFlickersBackAfterOneMissIsStillTheSameEvent() {
        // Missed last time (one miss), seen now: continued, so the miss count can be reset.
        Plan plan = tracker.reconcile(List.of(open(7, "501", 0, 1, "a", "b")), List.of(found("501", 0, "a", "b")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(7L);
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void routesAndDirectionsAreTrackedSeparately() {
        List<Open> open = List.of(
                open(1, "501", 0, 0, "a", "b"),
                open(2, "501", 1, 0, "a", "b"),
                open(3, "504", 0, 0, "a", "b"));

        // The same vehicle ids appear in all three, which happens when vehicle numbers are reused.
        Plan plan = tracker.reconcile(open, List.of(found("501", 1, "a", "b")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(2L);
        assertThat(plan.missed()).extracting(Missed::id).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void whenOneOpenBunchOverlapsTwoNewOnesTheLargerOverlapContinuesIt() {
        Plan plan = tracker.reconcile(
                List.of(open(7, "501", 0, 0, "a", "b", "c", "d")),
                List.of(found("501", 0, "a", "b"), found("501", 0, "b", "c", "d")));

        assertThat(plan.continued()).singleElement().satisfies(seen -> {
            assertThat(seen.id()).isEqualTo(7L);
            assertThat(seen.candidate().vehicleIds()).containsExactly("b", "c", "d");
        });
        assertThat(plan.toOpen()).singleElement().satisfies(bunch -> assertThat(bunch.vehicleIds()).containsExactly("a", "b"));
    }

    @Test
    void whenTwoOpenBunchesMergeIntoOneTheLargerOverlapContinuesAndTheOtherIsMissed() {
        Plan plan = tracker.reconcile(
                List.of(open(1, "501", 0, 0, "a", "b"), open(2, "501", 0, 0, "b", "c", "d")),
                List.of(found("501", 0, "a", "b", "c", "d")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(2L);
        assertThat(plan.missed()).containsExactly(new Missed(1, 1));
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void anEqualOverlapGoesToTheEarlierOpenedEvent() {
        Plan plan = tracker.reconcile(
                List.of(open(1, "501", 0, 0, "a", "b"), open(2, "501", 0, 0, "a", "b")),
                List.of(found("501", 0, "a", "b")));

        assertThat(plan.continued()).singleElement().extracting(Continued::id).isEqualTo(1L);
        assertThat(plan.missed()).extracting(Missed::id).containsExactly(2L);
    }

    @Test
    void twoNewBunchesOnOneDirectionBothOpen() {
        Plan plan = tracker.reconcile(List.of(), List.of(found("501", 0, "a", "b"), found("501", 0, "c", "d")));

        assertThat(plan.toOpen()).hasSize(2);
    }

    @Test
    void nothingOpenAndNothingFoundIsNothingToDo() {
        Plan plan = tracker.reconcile(List.of(), List.of());

        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.continued()).isEmpty();
        assertThat(plan.missed()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void everyOpenBunchEndsUpInExactlyOneOutcome() {
        List<Open> open = List.of(
                open(1, "501", 0, 0, "a", "b"),
                open(2, "501", 0, 1, "c", "d"),
                open(3, "504", 1, 0, "e", "f"));

        Plan plan = tracker.reconcile(open, List.of(found("501", 0, "a", "b"), found("504", 0, "x", "y")));

        long accountedFor = plan.continued().size() + plan.missed().size() + plan.toResolve().size();
        assertThat(accountedFor).isEqualTo(open.size());
        assertThat(plan.continued()).extracting(Continued::id).containsExactly(1L);
        assertThat(plan.toResolve()).containsExactly(2L);
        assertThat(plan.missed()).extracting(Missed::id).containsExactly(3L);
        assertThat(plan.toOpen()).hasSize(1);
    }
}
