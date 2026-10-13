package ca.trackline.api.analytics.health;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How fast vehicles are making progress, measured from where they have been.
 */
class RouteSpeedEstimatorTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");

    private RouteSpeedEstimator estimator;

    @BeforeEach
    void freshEstimator() {
        estimator = new RouteSpeedEstimator(HealthFixtures.defaults());
    }

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    /** A vehicle seen four times over three minutes, getting {@code metresPerSecond} along. */
    private void watch(String vehicle, String route, short direction, double metresPerSecond) {
        for (int second = 0; second <= 180; second += 60) {
            estimator.observe(vehicle, route, direction, 1000 + metresPerSecond * second, at(second));
        }
    }

    @Test
    void aRoutesSpeedIsTheMedianOfItsVehicles() {
        watch("a", "501", (short) 0, 4.0);
        watch("b", "501", (short) 0, 6.0);
        watch("c", "501", (short) 0, 5.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void anEvenNumberOfVehiclesAveragesTheTwoMiddleOnes() {
        watch("a", "501", (short) 0, 2.0);
        watch("b", "501", (short) 0, 4.0);
        watch("c", "501", (short) 0, 6.0);
        watch("d", "501", (short) 0, 8.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void aMinorityOfParkedVehiclesDoesNotDragTheMedianDown() {
        watch("moving-1", "501", (short) 0, 5.0);
        watch("moving-2", "501", (short) 0, 5.0);
        watch("moving-3", "501", (short) 0, 5.0);
        watch("layover", "501", (short) 0, 0.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void fewerThanThreeVehiclesGiveNoSpeed() {
        watch("a", "501", (short) 0, 5.0);
        watch("b", "501", (short) 0, 5.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void aVehicleWatchedForLessThanThreeMinutesContributesNothing() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            estimator.observe(vehicle, "501", (short) 0, 1000, at(0));
            estimator.observe(vehicle, "501", (short) 0, 1300, at(60));
            estimator.observe(vehicle, "501", (short) 0, 1600, at(120));
        }

        // Two minutes of readings is under the three-minute span.
        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void aVehicleWithTooFewReadingsContributesNothing() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            estimator.observe(vehicle, "501", (short) 0, 1000, at(0));
            estimator.observe(vehicle, "501", (short) 0, 2000, at(200));
        }

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void aVehicleThatWentBackwardsIsNotASpeed() {
        // Backwards is a projection flip or a vehicle that doubled back, never a negative speed.
        watch("a", "501", (short) 0, 5.0);
        watch("b", "501", (short) 0, 5.0);
        watch("c", "501", (short) 0, 5.0);
        watch("backwards", "501", (short) 0, -3.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void aRouteWhoseMedianVehicleIsParkedHasNoSpeedRatherThanARidiculousOne() {
        // The first live run produced 0.06 m/s on a route whose vehicles were sitting out a layover,
        // which turned a timetable spacing into 48 m.
        watch("a", "501", (short) 0, 0.05);
        watch("b", "501", (short) 0, 0.07);
        watch("c", "501", (short) 0, 0.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void aSpeedExactlyAtTheFloorIsStillASpeed() {
        watch("a", "501", (short) 0, 1.0);
        watch("b", "501", (short) 0, 1.0);
        watch("c", "501", (short) 0, 1.0);

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void aSpeedNoBusCouldReachIsAProjectionErrorAndIsDiscarded() {
        watch("a", "501", (short) 0, 5.0);
        watch("b", "501", (short) 0, 5.0);
        watch("jumped", "501", (short) 0, 40.0);

        // Only two plausible vehicles remain, which is under the minimum.
        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void aReadingNoNewerThanTheLastIsIgnored() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
            // The feed repeats a vehicle that has not reported again.
            estimator.observe(vehicle, "501", (short) 0, 99_999, at(180));
            estimator.observe(vehicle, "501", (short) 0, 99_999, at(10));
        }

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void turningRoundStartsAFreshRunSoTheTwoDirectionsAreNeverMixed() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
            // The same vehicle now on the other direction's shape, at a position that is unrelated.
            estimator.observe(vehicle, "501", (short) 1, 100, at(240));
        }

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
        // One reading is not a run long enough to measure.
        assertThat(estimator.speedMetresPerSecond("501", (short) 1)).isEmpty();
    }

    @Test
    void routesAndDirectionsAreMeasuredSeparately() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
            watch(vehicle + "-other", "504", (short) 0, 8.0);
        }

        assertThat(estimator.speedMetresPerSecond("501", (short) 0).orElseThrow()).isCloseTo(5.0, within(0.001));
        assertThat(estimator.speedMetresPerSecond("504", (short) 0).orElseThrow()).isCloseTo(8.0, within(0.001));
        assertThat(estimator.speedMetresPerSecond("501", (short) 1)).isEmpty();
        assertThat(estimator.speedMetresPerSecond("999", (short) 0)).isEmpty();
    }

    @Test
    void readingsOlderThanTheWindowAreDroppedAndSoAreVehiclesThatStoppedReporting() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
        }
        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isPresent();

        // Twenty minutes on, with nothing new: the 15-minute window has emptied.
        estimator.prune(T0.plus(Duration.ofMinutes(20)));

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }

    @Test
    void pruningKeepsWhatIsStillInsideTheWindow() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
        }

        estimator.prune(T0.plus(Duration.ofMinutes(5)));

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isPresent();
    }

    @Test
    void forgettingStartsOver() {
        for (String vehicle : new String[] {"a", "b", "c"}) {
            watch(vehicle, "501", (short) 0, 5.0);
        }

        estimator.forget();

        assertThat(estimator.speedMetresPerSecond("501", (short) 0)).isEmpty();
    }
}
