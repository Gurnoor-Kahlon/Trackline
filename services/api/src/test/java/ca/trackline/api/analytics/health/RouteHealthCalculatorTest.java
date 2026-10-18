package ca.trackline.api.analytics.health;

import java.util.Collections;
import java.util.List;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionInputs;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The judgement itself, on arithmetic: no database, no clock, no feed.
 *
 * <p>Boundaries are tested on both sides of every threshold, because an off-by-one between
 * "above" and "at or above" would quietly move every route sitting on a boundary into the
 * next band. The figures are chosen to be exact: two gaps a and b have a coefficient of
 * variation of |a - b| / (a + b), so 500 and 300 give exactly 0.25.
 */
class RouteHealthCalculatorTest {

    private static final String ROUTE = "501";
    private static final double LENGTH = 9_000;

    private final RouteHealthCalculator calculator = new RouteHealthCalculator(HealthFixtures.defaults());

    /**
     * Inputs for a timetable comparison: scheduled spacing 600 s at 5 m/s is an expected
     * gap of 3,000 m, so a 9,000 m route should carry three vehicles.
     */
    private static DirectionInputs scheduled(List<Double> gaps, int fresh) {
        return new DirectionInputs(
                ROUTE, (short) 0, gaps, gaps.size() + 1, fresh, 0, 0, LENGTH, false, true, 600.0, 5.0);
    }

    /** Inputs with nothing to compare against: only the vehicles' own spacing is known. */
    private static DirectionInputs observedOnly(List<Double> gaps) {
        return new DirectionInputs(
                ROUTE, (short) 0, gaps, gaps.size() + 1, gaps.size() + 1, 0, 0, LENGTH, false, false, null, null);
    }

    private DirectionHealth judge(DirectionInputs inputs) {
        return calculator.evaluate(inputs);
    }

    // --- not enough to judge --------------------------------------------------------------------

    @Test
    void noVehiclesIsUnknownWithNoConfidence() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(), 0, 0, 0, 0, LENGTH, false, true, 600.0, 5.0));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(health.confidence()).isEqualTo(HealthConfidence.NONE);
        assertThat(health.reasons()).containsExactly(ReasonCode.INSUFFICIENT_VEHICLES);
    }

    @Test
    void oneVehicleIsUnknownBecauseOneVehicleHasNoSpacing() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(), 1, 1, 0, 0, LENGTH, false, true, 600.0, 5.0));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNKNOWN);
    }

    @Test
    void anUnknownVerdictStatesNoFigureRatherThanZeros() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(), 1, 1, 0, 0, LENGTH, false, true, 600.0, 5.0));

        assertThat(health.metrics().meanGapMetres()).isNull();
        assertThat(health.metrics().variation()).isNull();
        assertThat(health.metrics().gapRatio()).isNull();
        assertThat(health.metrics().supplyRatio()).isNull();
    }

    // --- the best case ---------------------------------------------------------------------------

    @Test
    void evenlySpacedVehiclesAgainstTheTimetableAreExcellentWithHighConfidence() {
        // Five vehicles at the scheduled 3,000 m, on a route that needs three: more than enough.
        DirectionHealth health = judge(scheduled(List.of(3000.0, 3000.0, 3000.0, 3000.0), 5));

        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
        assertThat(health.confidence()).isEqualTo(HealthConfidence.HIGH);
        assertThat(health.reasons()).isEmpty();
        assertThat(health.metrics().variation()).isZero();
        assertThat(health.metrics().gapRatio()).isEqualTo(1.0);
        assertThat(health.metrics().basis()).isEqualTo(SpacingBasis.SCHEDULE);
    }

    @Test
    void theMetricsCarryTheFiguresTheVerdictWasReachedFrom() {
        DirectionHealth health = judge(scheduled(List.of(2000.0, 4000.0, 3000.0), 4));

        assertThat(health.metrics().vehicles()).isEqualTo(4);
        assertThat(health.metrics().gaps()).isEqualTo(3);
        assertThat(health.metrics().meanGapMetres()).isEqualTo(3000.0);
        assertThat(health.metrics().maxGapMetres()).isEqualTo(4000.0);
        assertThat(health.metrics().expectedGapMetres()).isEqualTo(3000.0);
        assertThat(health.metrics().expectedVehicles()).isEqualTo(3.0);
        assertThat(health.metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
        assertThat(health.metrics().speedMetresPerSecond()).isEqualTo(5.0);
        // Population standard deviation of 2000, 4000, 3000 is sqrt(2/3 * 1e6), over a mean of 3000.
        assertThat(health.metrics().variation()).isCloseTo(Math.sqrt(2.0 / 3.0 * 1e6) / 3000.0, within(1e-9));
    }

    // --- variation boundaries (own average as the yardstick, so only variation moves) -------------
    //
    // Four gaps, two of m + d and two of m - d: the mean is m and the standard deviation is d, so
    // the coefficient of variation is exactly d / m. Variation needs at least three gaps.

    @Test
    void variationExactlyAtTheExcellentBandIsStillExcellent() {
        // m = 400, d = 100: 0.25 exactly. The own-mean ratio is 1.25, under 1.3.
        DirectionHealth health = judge(observedOnly(List.of(500.0, 500.0, 300.0, 300.0)));

        assertThat(health.metrics().variation()).isEqualTo(0.25);
        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    @Test
    void variationJustAboveTheExcellentBandIsNormal() {
        // m = 100, d = 26: 0.26. The ratio is 1.26, under 1.3, so it is the variation that decides.
        assertThat(judge(observedOnly(List.of(126.0, 126.0, 74.0, 74.0))).state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void variationExactlyAtTheUnstableBandIsStillNormal() {
        // m = 2, d = 1: 0.5 exactly. The own-mean ratio is 1.5, between 1.3 and 1.8.
        assertThat(judge(observedOnly(List.of(3.0, 3.0, 1.0, 1.0))).state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void variationJustAboveTheUnstableBandIsUnstable() {
        // m = 100, d = 51: 0.51. The ratio is 1.51.
        DirectionHealth health = judge(observedOnly(List.of(151.0, 151.0, 49.0, 49.0)));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNSTABLE);
        assertThat(health.reasons()).contains(ReasonCode.HIGH_HEADWAY_VARIATION);
    }

    @Test
    void variationExactlyAtTheMajorBandIsStillUnstable() {
        // m = 4, d = 3: 0.75 exactly. The own-mean ratio is 1.75, under 1.8.
        assertThat(judge(observedOnly(List.of(7.0, 7.0, 1.0, 1.0))).state()).isEqualTo(RouteHealthState.UNSTABLE);
    }

    @Test
    void variationJustAboveTheMajorBandIsAMajorDisruption() {
        // m = 100, d = 76: 0.76. The ratio is 1.76, so it is the variation that decides.
        DirectionHealth health = judge(observedOnly(List.of(176.0, 176.0, 24.0, 24.0)));

        assertThat(health.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
        assertThat(health.reasons()).contains(ReasonCode.HIGH_HEADWAY_VARIATION);
    }

    // --- gap-ratio boundaries (equal gaps, so variation is zero and only the ratio moves) -----------

    private DirectionHealth withEqualGapsOf(double gapMetres) {
        // Three vehicles, two equal gaps. A 9,000 m route at 3,000 m expects three vehicles.
        return judge(scheduled(List.of(gapMetres, gapMetres), 3));
    }

    @Test
    void aGapExactlyAtTheExcellentRatioIsStillExcellent() {
        assertThat(withEqualGapsOf(3900).state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    @Test
    void aGapJustAboveTheExcellentRatioIsNormal() {
        assertThat(withEqualGapsOf(3930).state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void aGapExactlyAtTheUnstableRatioIsStillNormal() {
        assertThat(withEqualGapsOf(5400).state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void aGapJustAboveTheUnstableRatioIsUnstable() {
        DirectionHealth health = withEqualGapsOf(5430);

        assertThat(health.state()).isEqualTo(RouteHealthState.UNSTABLE);
        assertThat(health.reasons()).contains(ReasonCode.LARGE_GAP);
    }

    @Test
    void aGapExactlyAtTheMajorRatioIsStillUnstable() {
        // 7,500 is exactly 2.5 times 3,000. Supply is judged against the expected spacing of 3,000, not the observed one, so three vehicles on this route are still all it needs.
        assertThat(withEqualGapsOf(7500).state()).isEqualTo(RouteHealthState.UNSTABLE);
    }

    @Test
    void aGapJustAboveTheMajorRatioIsAMajorDisruption() {
        assertThat(withEqualGapsOf(7530).state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
    }

    @Test
    void oneBigHoleAmongSmallGapsIsCaughtEvenAgainstTheOwnAverage() {
        // No timetable at all. One 9 km hole among 1 km gaps is plainly a hole.
        DirectionHealth health = judge(observedOnly(List.of(1000.0, 1000.0, 1000.0, 9000.0)));

        assertThat(health.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
    }

    // --- supply ----------------------------------------------------------------------------------

    @Test
    void exactlyHalfTheScheduledVehiclesIsNotYetAMajorDisruption() {
        // A 30,000 m route at 3,000 m needs 10 vehicles; 5 is exactly half. Spacing is even.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0, 3000.0, 3000.0), 5, 5, 0, 0, 30_000, false, true, 600.0, 5.0));

        assertThat(health.metrics().supplyRatio()).isEqualTo(0.5);
        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    @Test
    void fewerThanHalfTheScheduledVehiclesIsAMajorDisruptionEvenWhenSpacedEvenly() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0, 3000.0), 4, 4, 0, 0, 30_000, false, true, 600.0, 5.0));

        assertThat(health.metrics().supplyRatio()).isEqualTo(0.4);
        assertThat(health.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
        assertThat(health.reasons()).contains(ReasonCode.FEWER_VEHICLES_THAN_SCHEDULED);
    }

    @Test
    void supplyCountsVehiclesAtTerminalsEvenThoughTheirSpacingIsNotMeasured() {
        // Four vehicles in the spacing plus six waiting at the terminal: ten are running.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0, 3000.0), 4, 10, 0, 6, 30_000, false, true, 600.0, 5.0));

        assertThat(health.metrics().supplyRatio()).isEqualTo(1.0);
        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    @Test
    void aRouteLongerThanItsSpacingStillNeedsAtLeastOneVehicle() {
        // Expected spacing 3,000 m on a 1,000 m route: a fraction of a vehicle, rounded up to one.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(500.0, 500.0), 3, 3, 0, 0, 1_000, false, true, 600.0, 5.0));

        assertThat(health.metrics().expectedVehicles()).isEqualTo(1.0);
        assertThat(health.metrics().supplyRatio()).isEqualTo(3.0);
    }

    // --- confidence ------------------------------------------------------------------------------

    @Test
    void twoVehiclesGiveLowConfidenceAndNoVariationFigure() {
        DirectionHealth health = judge(scheduled(List.of(3000.0), 2));

        assertThat(health.confidence()).isEqualTo(HealthConfidence.LOW);
        assertThat(health.metrics().variation()).isNull();
        assertThat(health.reasons()).contains(ReasonCode.FEW_VEHICLES);
    }

    @Test
    void lowConfidenceNeverReportsBetterThanNormal() {
        // One perfectly even gap between two buses says almost nothing about the rest of the route.
        DirectionHealth health = judge(scheduled(List.of(3000.0), 2));

        assertThat(health.state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void lowConfidenceIsNeverCalledAMajorDisruptionButStillSaysWhatIsWrong() {
        // Two buses 20 km apart against 3 km expected, on a route that needs ten. It is bad
        // news, but two buses may just be a route with two buses at this hour, so the verdict
        // stops at unstable and the reasons carry the rest.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(20_000.0), 2, 2, 0, 0, 30_000, false, true, 600.0, 5.0));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNSTABLE);
        assertThat(health.confidence()).isEqualTo(HealthConfidence.LOW);
        assertThat(health.reasons()).contains(ReasonCode.LARGE_GAP, ReasonCode.FEWER_VEHICLES_THAN_SCHEDULED);
    }

    @Test
    void twoVehiclesWithNoTimetableAreNotJudgedBecauseTheirOwnAverageSaysNothing() {
        // One gap against its own mean is always exactly average, so any state would be an
        // artefact of the method. This is the case that read "normal" on the first live run.
        DirectionHealth health = judge(observedOnly(List.of(4000.0)));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(health.confidence()).isEqualTo(HealthConfidence.NONE);
        assertThat(health.reasons()).containsExactly(ReasonCode.NO_SCHEDULE_COVERAGE, ReasonCode.FEW_VEHICLES);
        assertThat(health.metrics().gapRatio()).isNull();
        assertThat(health.metrics().meanGapMetres()).isEqualTo(4000.0);
    }

    @Test
    void twoVehiclesAreJudgedOnceThereIsATimetableToCompareThemWith() {
        DirectionHealth health = judge(scheduled(List.of(3000.0), 2));

        assertThat(health.state()).isNotEqualTo(RouteHealthState.UNKNOWN);
        assertThat(health.metrics().gapRatio()).isEqualTo(1.0);
    }

    @Test
    void threeVehiclesWithNoTimetableAreJudgedOnTheirLargestGapAgainstTheAverage() {
        // Two gaps, so no variation figure, but one wide hole shows in the ratio.
        DirectionHealth health = judge(observedOnly(List.of(500.0, 5000.0)));

        assertThat(health.metrics().variation()).isNull();
        assertThat(health.metrics().gapRatio()).isCloseTo(1.82, within(0.01));
        assertThat(health.state()).isEqualTo(RouteHealthState.UNSTABLE);
    }

    @Test
    void variationNeedsThreeGapsBecauseTwoSaturate() {
        assertThat(judge(scheduled(List.of(1000.0, 5000.0), 3)).metrics().variation()).isNull();
        assertThat(judge(scheduled(List.of(1000.0, 5000.0, 3000.0), 4)).metrics().variation()).isNotNull();
    }

    @Test
    void threeAndFourVehiclesAreMediumConfidence() {
        assertThat(judge(scheduled(List.of(3000.0, 3000.0), 3)).confidence()).isEqualTo(HealthConfidence.MEDIUM);
        assertThat(judge(scheduled(List.of(3000.0, 3000.0, 3000.0), 4)).confidence()).isEqualTo(HealthConfidence.MEDIUM);
    }

    @Test
    void fiveVehiclesWithATimetableAreHighConfidence() {
        assertThat(judge(scheduled(Collections.nCopies(4, 3000.0), 5)).confidence()).isEqualTo(HealthConfidence.HIGH);
    }

    @Test
    void fiveVehiclesWithoutATimetableAreOnlyMediumConfidence() {
        assertThat(judge(observedOnly(Collections.nCopies(4, 3000.0))).confidence())
                .isEqualTo(HealthConfidence.MEDIUM);
    }

    @Test
    void moreThanAThirdStaleMeansLowConfidenceAndSaysWhy() {
        // 6 fresh and 4 stale: 40 % stale, over the 30 % line.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, Collections.nCopies(5, 3000.0), 6, 6, 4, 0, LENGTH, false, true, 600.0, 5.0));

        assertThat(health.confidence()).isEqualTo(HealthConfidence.LOW);
        assertThat(health.reasons()).contains(ReasonCode.STALE_VEHICLES);
    }

    @Test
    void exactlyThirtyPercentStaleIsNotYetLowConfidence() {
        // 7 fresh and 3 stale: exactly 30 %, which is not above the line.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, Collections.nCopies(6, 3000.0), 7, 7, 3, 0, LENGTH, false, true, 600.0, 5.0));

        assertThat(health.confidence()).isEqualTo(HealthConfidence.HIGH);
        assertThat(health.reasons()).doesNotContain(ReasonCode.STALE_VEHICLES);
    }

    // --- what the comparison is made against -------------------------------------------------------

    @Test
    void withoutScheduleCoverageSpacingIsJudgedAgainstItselfAndSaysSo() {
        DirectionHealth health = judge(observedOnly(List.of(3000.0, 3000.0, 3000.0)));

        assertThat(health.metrics().basis()).isEqualTo(SpacingBasis.OBSERVED);
        assertThat(health.reasons()).contains(ReasonCode.NO_SCHEDULE_COVERAGE);
        assertThat(health.metrics().expectedGapMetres()).isNull();
        assertThat(health.metrics().supplyRatio()).isNull();
    }

    @Test
    void aTimetableWithNoSpacingForThisHourSaysSo() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0), 3, 3, 0, 0, LENGTH, false, true, null, 5.0));

        assertThat(health.reasons()).contains(ReasonCode.NO_SCHEDULED_HEADWAY);
        assertThat(health.metrics().basis()).isEqualTo(SpacingBasis.OBSERVED);
    }

    @Test
    void aTimetableSpacingWithNoMeasuredSpeedYetSaysSo() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0), 3, 3, 0, 0, LENGTH, false, true, 600.0, null));

        assertThat(health.reasons()).contains(ReasonCode.SPEED_UNKNOWN);
        assertThat(health.metrics().basis()).isEqualTo(SpacingBasis.OBSERVED);
        assertThat(health.metrics().scheduledHeadwaySeconds()).isEqualTo(600.0);
    }

    @Test
    void withoutATimetableUniformlyWideSpacingCannotBeSeenAsAProblem() {
        // The known limit of judging spacing against itself: every gap 10 km, no way to know
        // 10 km is too many. With the timetable the same spacing is flagged (next test).
        DirectionHealth health = judge(observedOnly(List.of(10_000.0, 10_000.0, 10_000.0)));

        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    @Test
    void theSameWideSpacingIsFlaggedOnceThereIsATimetableToCompareWith() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(10_000.0, 10_000.0, 10_000.0), 4, 4, 0, 0, 40_000, false, true, 600.0, 5.0));

        assertThat(health.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
        assertThat(health.metrics().gapRatio()).isCloseTo(3.33, within(0.01));
    }

    @Test
    void aLoopIsJudgedOnAsManyGapsAsVehiclesIncludingTheOneThatWrapsAround() {
        // Three vehicles on a 9,000 m loop at 3,000 m each: the third gap is the wrap.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(3000.0, 3000.0, 3000.0), 3, 3, 0, 0, 9_000, true, true, 600.0, 5.0));

        assertThat(health.metrics().gaps()).isEqualTo(3);
        assertThat(health.state()).isEqualTo(RouteHealthState.EXCELLENT);
    }

    // --- combining directions -----------------------------------------------------------------------

    private static DirectionHealth direction(short id, RouteHealthState state, HealthConfidence confidence, ReasonCode... reasons) {
        return new DirectionHealth(id, state, confidence, List.of(reasons), null);
    }

    @Test
    void aRouteIsAsBadAsItsWorseDirection() {
        RouteHealth route = calculator.combine(
                ROUTE,
                List.of(
                        direction((short) 0, RouteHealthState.EXCELLENT, HealthConfidence.HIGH),
                        direction((short) 1, RouteHealthState.UNSTABLE, HealthConfidence.MEDIUM, ReasonCode.LARGE_GAP)),
                0);

        assertThat(route.state()).isEqualTo(RouteHealthState.UNSTABLE);
        assertThat(route.confidence()).isEqualTo(HealthConfidence.MEDIUM);
        // The explanation is that of the direction that set the state.
        assertThat(route.reasons()).containsExactly(ReasonCode.LARGE_GAP);
        assertThat(route.directions()).hasSize(2);
    }

    @Test
    void aDirectionWithNoVerdictDoesNotDragARouteDownOrHideTheOtherDirection() {
        RouteHealth route = calculator.combine(
                ROUTE,
                List.of(
                        direction((short) 0, RouteHealthState.UNKNOWN, HealthConfidence.NONE, ReasonCode.INSUFFICIENT_VEHICLES),
                        direction((short) 1, RouteHealthState.NORMAL, HealthConfidence.MEDIUM)),
                0);

        assertThat(route.state()).isEqualTo(RouteHealthState.NORMAL);
    }

    @Test
    void aRouteWithNoVerdictInEitherDirectionIsUnknown() {
        RouteHealth route = calculator.combine(
                ROUTE,
                List.of(
                        direction((short) 0, RouteHealthState.UNKNOWN, HealthConfidence.NONE, ReasonCode.INSUFFICIENT_VEHICLES),
                        direction((short) 1, RouteHealthState.UNKNOWN, HealthConfidence.NONE, ReasonCode.INSUFFICIENT_VEHICLES)),
                2);

        assertThat(route.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(route.confidence()).isEqualTo(HealthConfidence.NONE);
        assertThat(route.unplacedVehicles()).isEqualTo(2);
    }

    @Test
    void aRouteWithNoDirectionsAtAllIsUnknownForWantOfVehicles() {
        RouteHealth route = calculator.combine(ROUTE, List.of(), 3);

        assertThat(route.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(route.reasons()).containsExactly(ReasonCode.INSUFFICIENT_VEHICLES);
        assertThat(route.unplacedVehicles()).isEqualTo(3);
    }

    @Test
    void whenBothDirectionsAreEquallyBadTheWeakerEvidenceIsReported() {
        RouteHealth route = calculator.combine(
                ROUTE,
                List.of(
                        direction((short) 0, RouteHealthState.UNSTABLE, HealthConfidence.HIGH, ReasonCode.LARGE_GAP),
                        direction((short) 1, RouteHealthState.UNSTABLE, HealthConfidence.LOW, ReasonCode.HIGH_HEADWAY_VARIATION)),
                0);

        assertThat(route.confidence()).isEqualTo(HealthConfidence.LOW);
        assertThat(route.reasons()).containsExactly(ReasonCode.HIGH_HEADWAY_VARIATION);
    }

    @Test
    void aMajorDisruptionInOneDirectionOutranksAnUnstableOtherEvenWithWeakerEvidence() {
        RouteHealth route = calculator.combine(
                ROUTE,
                List.of(
                        direction((short) 0, RouteHealthState.UNSTABLE, HealthConfidence.HIGH),
                        direction((short) 1, RouteHealthState.MAJOR_DISRUPTION, HealthConfidence.LOW)),
                0);

        assertThat(route.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
    }

    // --- the types ---------------------------------------------------------------------------------

    @Test
    void worseOfTwoStatesIgnoresUnknown() {
        assertThat(RouteHealthState.worse(RouteHealthState.UNKNOWN, RouteHealthState.NORMAL)).isEqualTo(RouteHealthState.NORMAL);
        assertThat(RouteHealthState.worse(RouteHealthState.NORMAL, RouteHealthState.UNKNOWN)).isEqualTo(RouteHealthState.NORMAL);
        assertThat(RouteHealthState.worse(RouteHealthState.UNSTABLE, RouteHealthState.EXCELLENT)).isEqualTo(RouteHealthState.UNSTABLE);
        assertThat(RouteHealthState.worse(RouteHealthState.UNKNOWN, RouteHealthState.UNKNOWN)).isEqualTo(RouteHealthState.UNKNOWN);
    }

    // --- bunching ---------------------------------------------------------------------------------------

    private static DirectionInputs evenButBunched(int clusters, int vehicles) {
        // Gaps that are perfectly even, so nothing but the bunching input could move the verdict.
        return new DirectionInputs(
                ROUTE, (short) 0, Collections.nCopies(vehicles - 1, 3000.0), vehicles, vehicles, 0, 0, LENGTH, false,
                true, 600.0, 5.0, clusters);
    }

    @Test
    void aBunchIsNamedAsAReasonAndCountedInTheFigures() {
        DirectionHealth health = judge(evenButBunched(1, 5));

        assertThat(health.reasons()).contains(ReasonCode.BUNCHING);
        assertThat(health.metrics().bunchingClusters()).isEqualTo(1);
    }

    @Test
    void aBunchMakesAnOtherwiseExcellentRouteAtLeastUnstable() {
        // With the pack and the gap measured against one yardstick the gap already does this, so the
        // rule is stated and tested on its own, with the figures that would have made it excellent.
        assertThat(judge(evenButBunched(0, 5)).state()).isEqualTo(RouteHealthState.EXCELLENT);
        assertThat(judge(evenButBunched(1, 5)).state()).isEqualTo(RouteHealthState.UNSTABLE);
    }

    @Test
    void aBunchDoesNotSoftenAWorseVerdict() {
        // 10,000 m gaps against 3,000 expected is a major disruption with or without a bunch.
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(10_000.0, 10_000.0, 10_000.0), 4, 4, 0, 0, 40_000, false, true, 600.0, 5.0, 1));

        assertThat(health.state()).isEqualTo(RouteHealthState.MAJOR_DISRUPTION);
        assertThat(health.reasons()).contains(ReasonCode.BUNCHING);
    }

    @Test
    void noBunchMeansNoBunchingReason() {
        assertThat(judge(evenButBunched(0, 5)).reasons()).doesNotContain(ReasonCode.BUNCHING);
        assertThat(judge(evenButBunched(0, 5)).metrics().bunchingClusters()).isZero();
    }

    @Test
    void aDirectionWithTooFewVehiclesIsNotJudgedEvenIfItReportsABunch() {
        DirectionHealth health = judge(new DirectionInputs(
                ROUTE, (short) 0, List.of(), 1, 1, 0, 0, LENGTH, false, true, 600.0, 5.0, 1));

        assertThat(health.state()).isEqualTo(RouteHealthState.UNKNOWN);
        assertThat(health.reasons()).doesNotContain(ReasonCode.BUNCHING);
    }

    @Test
    void severalBunchesAreCountedNotJustOne() {
        assertThat(judge(evenButBunched(3, 8)).metrics().bunchingClusters()).isEqualTo(3);
    }
}
