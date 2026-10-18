package ca.trackline.api.analytics.bunching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingModels.SpacedVehicle;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fixture matrix for bunching: what is a bunch, what is not, and where each threshold sits.
 *
 * <p>Every case uses an expected spacing of 3,000 m, so "close" is under 1,050 m, a reported gap
 * is over 5,400 m, and a severe gap is over 7,500 m. Boundaries are tested on both sides because
 * an off-by-one between "under" and "at or under" would move every vehicle sitting on a threshold.
 */
class BunchingDetectorTest {

    private static final double EXPECTED = 3_000;
    private static final double LINE = 30_000;

    private final BunchingDetector detector = new BunchingDetector(BunchingFixtures.defaults());

    private static SpacedVehicle v(String id, double progress) {
        return new SpacedVehicle(id, progress);
    }

    private List<BunchingCandidate> onLine(SpacedVehicle... vehicles) {
        return detector.detect("501", (short) 0, List.of(vehicles), LINE, false, EXPECTED, SpacingBasis.SCHEDULE);
    }

    private List<BunchingCandidate> onLoop(double length, double expected, SpacedVehicle... vehicles) {
        return detector.detect("501", (short) 0, List.of(vehicles), length, true, expected, SpacingBasis.SCHEDULE);
    }

    // --- what is not bunching -------------------------------------------------------------------------

    @Test
    void evenlySpacedVehiclesAreNotBunched() {
        assertThat(onLine(v("a", 3000), v("b", 6000), v("c", 9000), v("d", 12_000), v("e", 15_000))).isEmpty();
    }

    @Test
    void twoVehiclesCloseTogetherWithNoGapAnywhereAreNotBunching() {
        // The case that matters most: a pack, but nobody waits longer for it, because every other gap is ordinary.
        assertThat(onLine(v("a", 1000), v("b", 1500), v("c", 4000), v("d", 7000), v("e", 10_000))).isEmpty();
    }

    @Test
    void aRouteDenseEverywhereHasNoBunchBecauseThereIsNoGapForAnyoneToWaitIn() {
        assertThat(onLine(v("a", 1000), v("b", 1200), v("c", 1400), v("d", 1600))).isEmpty();
    }

    @Test
    void fewerThanThreeVehiclesAreNeverJudged() {
        // Two vehicles side by side on a 30 km route: no other gap can be established with so few.
        assertThat(onLine(v("a", 1000), v("b", 1200))).isEmpty();
        assertThat(onLine(v("a", 1000))).isEmpty();
        assertThat(onLine()).isEmpty();
    }

    @Test
    void anExpectedSpacingThatIsNotPositiveJudgesNothing() {
        assertThat(detector.detect("501", (short) 0, List.of(v("a", 0), v("b", 10), v("c", 9000)), LINE, false, 0, SpacingBasis.OBSERVED))
                .isEmpty();
        assertThat(detector.detect("501", (short) 0, List.of(v("a", 0), v("b", 10), v("c", 9000)), LINE, false, -5, SpacingBasis.OBSERVED))
                .isEmpty();
    }

    // --- what is ---------------------------------------------------------------------------------------

    @Test
    void twoBunchedVehiclesWithAWideGapElsewhereAreAModerateBunch() {
        // 500 m apart, and 6,500 m to the next vehicle: 2.17 times the expected spacing.
        List<BunchingCandidate> found = onLine(v("a", 1000), v("b", 1500), v("c", 8000), v("d", 11_000), v("e", 14_000));

        assertThat(found).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicleIds()).containsExactly("a", "b");
            assertThat(bunch.spanMetres()).isEqualTo(500.0);
            assertThat(bunch.serviceGapMetres()).isEqualTo(6500.0);
            assertThat(bunch.expectedGapMetres()).isEqualTo(3000.0);
            assertThat(bunch.severity()).isEqualTo(BunchingSeverity.MODERATE);
            assertThat(bunch.basis()).isEqualTo(SpacingBasis.SCHEDULE);
            assertThat(bunch.routeId()).isEqualTo("501");
        });
    }

    @Test
    void threeBunchedVehiclesAreSevereEvenWhenTheGapIsOnlyModerate() {
        // The gap, 6,000 m, is 2.0 times the expected spacing: moderate on its own.
        List<BunchingCandidate> found = onLine(v("a", 1000), v("b", 1300), v("c", 1600), v("d", 7600), v("e", 10_600));

        assertThat(found).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicleIds()).containsExactly("a", "b", "c");
            assertThat(bunch.spanMetres()).isEqualTo(600.0);
            assertThat(bunch.severity()).isEqualTo(BunchingSeverity.SEVERE);
        });
    }

    @Test
    void twoBunchedVehiclesWithAHugeGapAreSevereOnTheGapAlone() {
        List<BunchingCandidate> found = onLine(v("a", 1000), v("b", 1400), v("c", 14_000), v("d", 17_000));

        assertThat(found).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicles()).isEqualTo(2);
            assertThat(bunch.serviceGapMetres()).isEqualTo(12_600.0);
            assertThat(bunch.severity()).isEqualTo(BunchingSeverity.SEVERE);
        });
    }

    @Test
    void theBunchCanBeAnywhereAlongTheRouteIncludingTheFarEnd() {
        List<BunchingCandidate> found = onLine(v("a", 1000), v("b", 4000), v("c", 20_000), v("d", 20_300));

        assertThat(found).singleElement().satisfies(bunch -> assertThat(bunch.vehicleIds()).containsExactly("c", "d"));
    }

    @Test
    void twoSeparatePacksAreTwoBunchesSharingTheSameServiceGap() {
        List<BunchingCandidate> found = onLine(
                v("a", 1000), v("b", 1400), v("c", 12_000), v("d", 12_500), v("e", 20_000));

        assertThat(found).hasSize(2);
        assertThat(found.get(0).vehicleIds()).containsExactly("a", "b");
        assertThat(found.get(1).vehicleIds()).containsExactly("c", "d");
        // The largest gap elsewhere on the direction is the one both are measured by.
        assertThat(found).extracting(BunchingCandidate::serviceGapMetres).containsOnly(10_600.0);
    }

    @Test
    void vehiclesAtTheSameSpotAreABunchWithNoSpan() {
        List<BunchingCandidate> found = onLine(v("a", 5000), v("b", 5000), v("c", 20_000));

        assertThat(found).singleElement().satisfies(bunch -> assertThat(bunch.spanMetres()).isZero());
    }

    @Test
    void theOrderVehiclesAreGivenInDoesNotMatter() {
        List<BunchingCandidate> sorted = onLine(v("a", 1000), v("b", 1500), v("c", 8000), v("d", 11_000));
        List<BunchingCandidate> shuffled = onLine(v("d", 11_000), v("b", 1500), v("c", 8000), v("a", 1000));

        assertThat(shuffled).isEqualTo(sorted);
    }

    @Test
    void idsAreSortedSoTheSameBunchAlwaysReadsTheSame() {
        List<BunchingCandidate> found = onLine(v("zeta", 1000), v("alpha", 1400), v("mid", 1700), v("far", 12_000));

        assertThat(found.getFirst().vehicleIds()).containsExactly("alpha", "mid", "zeta");
    }

    // --- thresholds, both sides ----------------------------------------------------------------------------

    @Test
    void aGapJustUnderTheCloseThresholdIsABunchAndAGapAtItIsNot() {
        // 0.35 times 3,000 is 1,050.
        assertThat(onLine(v("a", 1000), v("b", 2049), v("c", 14_000), v("d", 17_000))).hasSize(1);
        assertThat(onLine(v("a", 1000), v("b", 2050), v("c", 14_000), v("d", 17_000))).isEmpty();
    }

    @Test
    void aServiceGapExactlyAtTheReportingRatioIsNotReportedAndJustOverIs() {
        // 1.8 times 3,000 is 5,400: the other gap, from the second vehicle to the third.
        assertThat(onLine(v("a", 1000), v("b", 1400), v("c", 6800), v("d", 9800))).isEmpty();
        assertThat(onLine(v("a", 1000), v("b", 1400), v("c", 6801), v("d", 9801))).hasSize(1);
    }

    @Test
    void aServiceGapExactlyAtTheSevereRatioIsModerateAndJustOverIsSevere() {
        // 2.5 times 3,000 is 7,500.
        assertThat(onLine(v("a", 1000), v("b", 1400), v("c", 8900), v("d", 11_900)).getFirst().severity())
                .isEqualTo(BunchingSeverity.MODERATE);
        assertThat(onLine(v("a", 1000), v("b", 1400), v("c", 8901), v("d", 11_901)).getFirst().severity())
                .isEqualTo(BunchingSeverity.SEVERE);
    }

    @Test
    void aPackOfThreeIsSevereAndAPackOfTwoWithTheSameGapIsNot() {
        assertThat(onLine(v("a", 1000), v("b", 1300), v("c", 7000), v("d", 10_000)).getFirst().severity())
                .isEqualTo(BunchingSeverity.MODERATE);
        assertThat(onLine(v("a", 1000), v("b", 1300), v("x", 1600), v("c", 7600), v("d", 10_600)).getFirst().severity())
                .isEqualTo(BunchingSeverity.SEVERE);
    }

    @Test
    void exactlyThreeVehiclesAreEnoughToJudge() {
        assertThat(onLine(v("a", 1000), v("b", 1400), v("c", 14_000))).hasSize(1);
    }

    @Test
    void threeVehiclesJudgedAgainstTheirOwnAverageAreNotJudgedBecauseTheResultIsArithmetic() {
        // One close pair among three vehicles leaves the other gap at about twice the average whatever the
        // road looks like, so reporting it would only restate that a pair is close.
        List<SpacedVehicle> vehicles = List.of(v("a", 1000), v("b", 1300), v("c", 4200));

        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, 1550, SpacingBasis.OBSERVED)).isEmpty();
        // The same positions against a timetable spacing are a real finding.
        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, 1550, SpacingBasis.SCHEDULE)).hasSize(1);
    }

    @Test
    void fourVehiclesAreEnoughWithoutATimetable() {
        List<SpacedVehicle> vehicles = List.of(v("a", 1000), v("b", 1300), v("c", 9000), v("d", 12_000));

        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, 3000, SpacingBasis.OBSERVED)).hasSize(1);
    }

    @Test
    void aSmallerExpectedSpacingMakesTheSameGapsAProblem() {
        // 2,500 m between vehicles is ordinary at 3,000 m expected and a wide gap at 1,000 m.
        List<SpacedVehicle> vehicles = List.of(v("a", 1000), v("b", 1200), v("c", 3700), v("d", 6200));

        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, 3000, SpacingBasis.OBSERVED)).isEmpty();
        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, 1000, SpacingBasis.OBSERVED)).hasSize(1);
    }

    @Test
    void theBasisItWasGivenIsRecordedWithTheResult() {
        List<BunchingCandidate> found = detector.detect(
                "501", (short) 1, List.of(v("a", 1000), v("b", 1400), v("c", 14_000), v("d", 17_000)), LINE, false,
                EXPECTED, SpacingBasis.OBSERVED);

        assertThat(found.getFirst().basis()).isEqualTo(SpacingBasis.OBSERVED);
        assertThat(found.getFirst().directionId()).isEqualTo((short) 1);
    }

    // --- loops -------------------------------------------------------------------------------------------------

    @Test
    void aPackStraddlingTheStartOfALoopIsOnePackNotTwo() {
        // Ten kilometre loop, 2,500 m expected: close is under 875 m, a reported gap over 4,500 m.
        // 9,900, then past the seam to 100 and 600: three vehicles within 700 m of each other.
        List<BunchingCandidate> found = onLoop(10_000, 2500, v("a", 9900), v("b", 100), v("c", 600), v("d", 6000));

        assertThat(found).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicleIds()).containsExactly("a", "b", "c");
            // 200 m across the seam and 500 m beyond it.
            assertThat(bunch.spanMetres()).isEqualTo(700.0);
            assertThat(bunch.serviceGapMetres()).isEqualTo(5400.0);
            assertThat(bunch.severity()).isEqualTo(BunchingSeverity.SEVERE);
        });
    }

    @Test
    void aPairAcrossTheSeamOfALoopIsFoundWithItsOwnSeverity() {
        List<BunchingCandidate> found = onLoop(10_000, 2500, v("a", 9900), v("b", 100), v("c", 4000));

        assertThat(found).singleElement().satisfies(bunch -> {
            assertThat(bunch.vehicleIds()).containsExactly("a", "b");
            assertThat(bunch.spanMetres()).isEqualTo(200.0);
            // The wider of 3,900 m from b to c and 5,900 m from c back round to a.
            assertThat(bunch.serviceGapMetres()).isEqualTo(5900.0);
            assertThat(bunch.severity()).isEqualTo(BunchingSeverity.MODERATE);
        });
    }

    @Test
    void theGapThatWrapsRoundALoopCountsAsAGapElsewhere() {
        // Vehicles at 1,000 and 1,300 sit together; the 8,700 m from 1,300 round to 1,000 again is the gap.
        List<BunchingCandidate> found = onLoop(10_000, 2500, v("a", 1000), v("b", 1300), v("c", 1500));

        assertThat(found).singleElement().satisfies(bunch -> assertThat(bunch.serviceGapMetres()).isEqualTo(9500.0));
    }

    @Test
    void aLoopWhoseEveryNeighbourIsCloseHasNoGapAndNoBunch() {
        assertThat(onLoop(1_000, 2500, v("a", 100), v("b", 300), v("c", 500), v("d", 700), v("e", 900))).isEmpty();
    }

    @Test
    void aLoopEvenlySpacedAcrossItsLengthHasNoBunch() {
        assertThat(onLoop(10_000, 2500, v("a", 1000), v("b", 3500), v("c", 6000), v("d", 8500))).isEmpty();
    }

    // --- robustness ------------------------------------------------------------------------------------------------

    @Test
    void doesNotChangeTheListItWasGiven() {
        List<SpacedVehicle> given = new ArrayList<>(List.of(v("c", 14_000), v("a", 1000), v("b", 1400)));
        List<SpacedVehicle> copy = new ArrayList<>(given);

        detector.detect("501", (short) 0, given, LINE, false, EXPECTED, SpacingBasis.SCHEDULE);

        assertThat(given).isEqualTo(copy);
    }

    @Test
    void givesTheSameAnswerWhenTheSameVehiclesAreGivenTwice() {
        List<SpacedVehicle> vehicles = new ArrayList<>(List.of(v("a", 1000), v("b", 1400), v("c", 14_000), v("d", 17_000)));
        List<BunchingCandidate> first = detector.detect("501", (short) 0, vehicles, LINE, false, EXPECTED, SpacingBasis.SCHEDULE);
        Collections.reverse(vehicles);

        assertThat(detector.detect("501", (short) 0, vehicles, LINE, false, EXPECTED, SpacingBasis.SCHEDULE)).isEqualTo(first);
    }
}
