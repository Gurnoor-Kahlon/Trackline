package ca.trackline.api.analytics.health;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Distances between consecutive vehicles, along the route.
 */
class HeadwaySeriesTest {

    @Test
    void aLineHasOneFewerGapThanVehiclesInOrderAlongTheRoute() {
        assertThat(HeadwaySeries.gaps(List.of(1000.0, 4000.0, 2500.0), 9000, false))
                .containsExactly(1500.0, 1500.0);
    }

    @Test
    void theOrderVehiclesAreGivenInDoesNotMatter() {
        assertThat(HeadwaySeries.gaps(List.of(5000.0, 100.0, 3000.0, 900.0), 9000, false))
                .isEqualTo(HeadwaySeries.gaps(List.of(100.0, 900.0, 3000.0, 5000.0), 9000, false));
    }

    @Test
    void aLoopAddsTheGapThatWrapsPastTheEnd() {
        List<Double> gaps = HeadwaySeries.gaps(List.of(1000.0, 4000.0, 7000.0), 9000, true);

        // 3,000 and 3,000 between them, and 3,000 from the last back round past the start.
        assertThat(gaps).containsExactly(3000.0, 3000.0, 3000.0);
    }

    @Test
    void theGapsOfALoopAddUpToItsLength() {
        List<Double> gaps = HeadwaySeries.gaps(List.of(300.0, 1200.0, 2100.0, 6000.0), 8000, true);

        assertThat(gaps.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(8000.0, within(1e-9));
    }

    @Test
    void twoVehiclesOnALoopHaveTwoGaps() {
        assertThat(HeadwaySeries.gaps(List.of(1000.0, 3000.0), 8000, true)).containsExactly(2000.0, 6000.0);
    }

    @Test
    void twoVehiclesAtTheSameSpotAreAZeroGapNotOne() {
        // Bunched vehicles are the thing spacing exists to reveal, so a zero gap is kept.
        assertThat(HeadwaySeries.gaps(List.of(2000.0, 2000.0, 5000.0), 9000, false)).containsExactly(0.0, 3000.0);
    }

    @Test
    void fewerThanTwoVehiclesHaveNoGaps() {
        assertThat(HeadwaySeries.gaps(List.of(), 9000, false)).isEmpty();
        assertThat(HeadwaySeries.gaps(List.of(4000.0), 9000, false)).isEmpty();
        // A single vehicle on a loop is not a gap of the whole length: there is nothing to space it from.
        assertThat(HeadwaySeries.gaps(List.of(4000.0), 9000, true)).isEmpty();
    }

    @Test
    void doesNotChangeTheListItWasGiven() {
        List<Double> given = new java.util.ArrayList<>(List.of(5.0, 1.0, 3.0));

        HeadwaySeries.gaps(given, 10, false);

        assertThat(given).containsExactly(5.0, 1.0, 3.0);
    }
}
