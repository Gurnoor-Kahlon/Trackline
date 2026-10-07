package ca.trackline.api.analytics.progress;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

/**
 * Direction inference with the geometry already worked out.
 *
 * <p>Candidates are built directly, so each test states the situation it is about —
 * which way each shape runs, how far away it is, where the vehicle was a moment
 * ago — and nothing else. Every threshold boundary is pinned on both sides, because
 * an off-by-one in a comparison here silently moves vehicles between statuses and
 * no later stage would notice.
 */
class DirectionInferrerTest {

    /** Matches the configured defaults, which are the measured values. */
    private static final ProgressProperties THRESHOLDS = new ProgressProperties(150, 60, 30, Duration.ofMinutes(5), 15);

    private static final double LENGTH = 10_000;

    private final DirectionInferrer inferrer = new DirectionInferrer(THRESHOLDS);

    /** A shape 10 km long, so a fraction of 0.25 is 2,500 m and arithmetic stays readable. */
    private static ShapeCandidate shape(int direction, double offset, double fraction, Double tangent, Double previous) {
        return new ShapeCandidate((short) direction, offset, fraction, LENGTH, false, tangent, previous);
    }

    /**
     * The two directions of an east-west street: direction 0 runs east, direction 1
     * runs west. This is what nearly every real two-way route looks like.
     */
    private static List<ShapeCandidate> eastWestStreet(Double previousEast, Double previousWest) {
        return List.of(
                shape(0, 6, 0.25, 90.0, previousEast),
                shape(1, 9, 0.75, 270.0, previousWest));
    }

    @Test
    void aHeadingThatAgreesWithOneDirectionAssignsIt() {
        RouteProgress result = inferrer.infer("v1", 88f, eastWestStreet(null, null));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.directionId()).isEqualTo((short) 0);
        assertThat(result.progressMetres()).isCloseTo(2_500, within(1e-9));
        assertThat(result.progressFraction()).isEqualTo(0.25);
        assertThat(result.offsetMetres()).isEqualTo(6);
        assertThat(result.unknownReason()).isNull();
    }

    @Test
    void theOppositeHeadingAssignsTheOtherDirection() {
        RouteProgress result = inferrer.infer("v1", 272f, eastWestStreet(null, null));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.directionId()).isEqualTo((short) 1);
        // Progress is measured along the shape of the direction chosen, not the other.
        assertThat(result.progressMetres()).isCloseTo(7_500, within(1e-9));
        assertThat(result.offsetMetres()).isEqualTo(9);
    }

    @Test
    void theBearingToleranceIsInclusive() {
        // 150 against a tangent of 90 is exactly 60 apart.
        assertThat(inferrer.infer("v1", 150f, eastWestStreet(null, null)).status())
                .isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);

        // A hair past it no longer agrees with either direction, and with no earlier
        // reading nothing else can decide.
        RouteProgress outside = inferrer.infer("v1", 150.01f, eastWestStreet(null, null));
        assertThat(outside.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
    }

    @Test
    void bearingsAreComparedAroundTheCompassNotAsPlainNumbers() {
        // 355 and 5 are ten degrees apart. Subtracting them gives 350, which would
        // wrongly disagree with a shape running at 5.
        List<ShapeCandidate> north = List.of(
                shape(0, 4, 0.5, 5.0, null),
                shape(1, 4, 0.5, 185.0, null));

        RouteProgress result = inferrer.infer("v1", 355f, north);

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.directionId()).isEqualTo((short) 0);
    }

    @Test
    void aBearingOfExactlyThreeHundredSixtyIsDueNorth() {
        List<ShapeCandidate> north = List.of(
                shape(0, 4, 0.5, 0.0, null),
                shape(1, 4, 0.5, 180.0, null));

        assertThat(inferrer.infer("v1", 360f, north).directionId()).isEqualTo((short) 0);
    }

    @ParameterizedTest
    @CsvSource({"350, 10, 20", "10, 350, 20", "0, 180, 180", "0, 360, 0", "90, 270, 180", "720, 0, 0", "181, 0, 179"})
    void angularDifferenceIsTheShorterWayRound(double first, double second, double expected) {
        assertThat(DirectionInferrer.angularDifference(first, second)).isCloseTo(expected, within(1e-9));
    }

    // --- movement, when the heading does not decide ----------------------------------

    @Test
    void movementDecidesWhenBothShapesAgreeWithTheHeading() {
        // Both shapes run roughly the way the vehicle is heading, as they do around a
        // terminal loop. Only how far it has travelled along each tells them apart.
        List<ShapeCandidate> overlapping = List.of(
                shape(0, 5, 0.40, 90.0, 0.4000),
                shape(1, 5, 0.40, 110.0, 0.3900));   // moved forward 100 m along direction 1

        RouteProgress result = inferrer.infer("v1", 100f, overlapping);

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
        assertThat(result.directionId()).isEqualTo((short) 1);
    }

    @Test
    void movementDecidesWhenTheHeadingAgreesWithNeitherDirection() {
        // Turning a corner: heading perpendicular to both shapes, so no agreement.
        // Travelling east it advanced 200 m along direction 0 and, because that shape
        // runs west, retreated 200 m along direction 1.
        List<ShapeCandidate> street = eastWestStreet(0.23, 0.77);

        RouteProgress result = inferrer.infer("v1", 0f, street);

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
        assertThat(result.directionId()).isEqualTo((short) 0);
    }

    @Test
    void movementDecidesWhenTheFeedPublishedNoBearing() {
        // The shapes are antiparallel and the vehicle advanced along direction 1.
        RouteProgress result = inferrer.infer("v1", null, eastWestStreet(0.26, 0.74));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
        assertThat(result.directionId()).isEqualTo((short) 1);
    }

    /**
     * A shape 1,024 m long, because 30/1024 is exactly representable in binary. With
     * a decimal length the boundary test would depend on floating-point rounding
     * landing on the right side of 30, and would assert nothing about the comparison.
     */
    private static ShapeCandidate shapeMovedForward(double metres) {
        double length = 1_024;
        double fraction = 0.5;
        return new ShapeCandidate((short) 0, 5, fraction, length, false, null, fraction - metres / length);
    }

    @Test
    void movementBelowTheThresholdIsNoiseNotTravel() {
        // 29 m forward: a stationary vehicle's GPS wandering, not a vehicle underway.
        RouteProgress result = inferrer.infer("v1", null, List.of(shapeMovedForward(29)));

        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
        assertThat(result.unknownReason()).isEqualTo(UnknownReason.NO_FORWARD_MOVEMENT);
    }

    @Test
    void movementAtExactlyTheThresholdCounts() {
        RouteProgress result = inferrer.infer("v1", null, List.of(shapeMovedForward(30)));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
        assertThat(result.directionId()).isEqualTo((short) 0);
    }

    @Test
    void advancingAlongBothShapesDoesNotChooseEither() {
        // Possible where two shapes diverge and the vehicle was near both. Picking one
        // would be a coin flip presented as a fact.
        List<ShapeCandidate> both = List.of(
                shape(0, 5, 0.50, 0.0, 0.49),
                shape(1, 5, 0.50, 0.0, 0.49));

        RouteProgress result = inferrer.infer("v1", null, both);

        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
        assertThat(result.unknownReason()).isEqualTo(UnknownReason.CONFLICTING_MOVEMENT);
    }

    @Test
    void withNoEarlierReadingAnInconclusiveHeadingLeavesTheDirectionUnknown() {
        RouteProgress result = inferrer.infer("v1", 0f, eastWestStreet(null, null));

        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
        assertThat(result.unknownReason()).isEqualTo(UnknownReason.NO_PREVIOUS_READING);
        // No direction, so no progress: a position along an unspecified shape is not a
        // position. The nearest offset is kept as the evidence.
        assertThat(result.directionId()).isNull();
        assertThat(result.progressMetres()).isNull();
        assertThat(result.progressFraction()).isNull();
        assertThat(result.offsetMetres()).isEqualTo(6);
    }

    @Test
    void aVehicleThatRetreatedAlongEveryShapeHasNoForwardMovement() {
        // Only possible where the shapes diverge, since antiparallel ones cannot both
        // be retreated along; it is the case where the vehicle has evidently moved
        // and yet gained ground on neither.
        List<ShapeCandidate> street = eastWestStreet(0.30, 0.80);

        RouteProgress result = inferrer.infer("v1", null, street);

        assertThat(result.unknownReason()).isEqualTo(UnknownReason.NO_FORWARD_MOVEMENT);
    }

    @Test
    void theHeadingTakesPrecedenceOverMovement() {
        // Heading says direction 0; movement says direction 1. The heading is the
        // primary signal by design, so it wins, and movement is not consulted.
        RouteProgress result = inferrer.infer("v1", 90f, eastWestStreet(0.30, 0.70));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.directionId()).isEqualTo((short) 0);
    }

    // --- off-route and single shapes ---------------------------------------------------

    @Test
    void aVehicleFarFromEveryShapeIsOffRouteAndKeepsTheNearestOffset() {
        List<ShapeCandidate> far = List.of(
                shape(0, 612, 0.25, 90.0, null),
                shape(1, 480, 0.75, 270.0, null));

        RouteProgress result = inferrer.infer("v1", 90f, far);

        assertThat(result.status()).isEqualTo(ProjectionStatus.OFF_ROUTE);
        assertThat(result.offsetMetres()).isEqualTo(480);
        assertThat(result.directionId()).isNull();
        assertThat(result.progressMetres()).isNull();
    }

    @Test
    void theOffsetLimitIsInclusive() {
        assertThat(inferrer.infer("v1", 90f, List.of(shape(0, 150.0, 0.2, 90.0, null))).status())
                .isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(inferrer.infer("v1", 90f, List.of(shape(0, 150.01, 0.2, 90.0, null))).status())
                .isEqualTo(ProjectionStatus.OFF_ROUTE);
    }

    @Test
    void aShapeBeyondTheOffsetLimitCannotWinEvenIfItsHeadingAgrees() {
        // Direction 1 agrees perfectly with the heading but is 400 m away, so the
        // vehicle is not on it. Direction 0 is close but runs the wrong way.
        List<ShapeCandidate> candidates = List.of(
                shape(0, 20, 0.25, 270.0, null),
                shape(1, 400, 0.75, 90.0, null));

        RouteProgress result = inferrer.infer("v1", 90f, candidates);

        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
        assertThat(result.directionId()).isNull();
    }

    @Test
    void aRouteWithOneShapeAssignsAVehicleHeadingAlongIt() {
        RouteProgress result = inferrer.infer("v1", 92f, List.of(shape(1, 8, 0.6, 90.0, null)));

        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_BEARING);
        assertThat(result.directionId()).isEqualTo((short) 1);
    }

    @Test
    void aRouteWithOneShapeDoesNotAssignAVehicleHeadingAgainstIt() {
        // Contradicted by its own heading, and nothing else to go on.
        RouteProgress result = inferrer.infer("v1", 270f, List.of(shape(0, 8, 0.6, 90.0, null)));

        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
    }

    @Test
    void aShapeWithNoDirectionAtThatPointCannotAgreeWithAHeading() {
        List<ShapeCandidate> candidates = List.of(shape(0, 5, 0.5, null, null), shape(1, 5, 0.5, 270.0, null));

        RouteProgress result = inferrer.infer("v1", 90f, candidates);

        // Direction 1 disagrees and direction 0 has nothing to compare, so neither agrees.
        assertThat(result.status()).isEqualTo(ProjectionStatus.UNKNOWN_DIRECTION);
    }

    @Test
    void anEmptyCandidateListIsACallersBugNotAStatus() {
        assertThatIllegalArgumentException().isThrownBy(() -> inferrer.infer("v1", 90f, List.of()));
    }

    // --- loops ----------------------------------------------------------------------------

    @Test
    void movementAcrossTheSeamOfALoopIsAShortStepNotAWholeLapBackwards() {
        ShapeCandidate crossing = new ShapeCandidate((short) 0, 5, 0.001, LENGTH, true, 0.0, 0.999);

        // 0.001 - 0.999 is -9,980 m as plain arithmetic. On a loop the vehicle
        // actually moved forward 20 m, from just before the seam to just after.
        assertThat(crossing.forwardMovementMetres()).isCloseTo(20, within(1e-9));
    }

    @Test
    void movementBackAcrossTheSeamOfALoopIsAShortStepBackwards() {
        ShapeCandidate crossing = new ShapeCandidate((short) 0, 5, 0.999, LENGTH, true, 0.0, 0.001);

        assertThat(crossing.forwardMovementMetres()).isCloseTo(-20, within(1e-9));
    }

    @Test
    void theSameJumpOnAnOrdinaryShapeIsExactlyWhatItLooksLike() {
        ShapeCandidate jump = new ShapeCandidate((short) 0, 5, 0.001, LENGTH, false, 0.0, 0.999);

        assertThat(jump.forwardMovementMetres()).isCloseTo(-9_980, within(1e-9));
    }

    @Test
    void aLoopVehicleCrossingTheSeamIsAssignedByMovement() {
        ShapeCandidate crossing = new ShapeCandidate((short) 0, 5, 0.0015, LENGTH, true, null, 0.9980);

        RouteProgress result = inferrer.infer("v1", null, List.of(crossing));

        // 0.0015 - 0.9980 is nearly a lap backwards as plain arithmetic, and wraps to
        // +35 m, which clears the threshold. Without the wrap it would be unknown.
        assertThat(result.status()).isEqualTo(ProjectionStatus.ASSIGNED_BY_MOVEMENT);
    }

    @Test
    void noPreviousFractionMeansNoMovementFigureAtAll() {
        assertThat(shape(0, 5, 0.5, 90.0, null).forwardMovementMetres()).isNull();
    }
}
