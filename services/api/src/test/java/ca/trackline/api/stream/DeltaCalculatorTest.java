package ca.trackline.api.stream;

import java.util.LinkedHashMap;
import java.util.Map;

import ca.trackline.api.stream.DeltaCalculator.Delta;
import org.junit.jupiter.api.Test;

import static ca.trackline.api.stream.StreamTestData.LAT;
import static ca.trackline.api.stream.StreamTestData.LON;
import static ca.trackline.api.stream.StreamTestData.northMetres;
import static ca.trackline.api.stream.StreamTestData.tracked;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which vehicles are worth sending: those whose picture a client holds would change.
 */
class DeltaCalculatorTest {

    private static final long T0 = 1_000_000;

    private final DeltaCalculator calculator = new DeltaCalculator(5, 60);

    private static Map<String, Tracked> fleet(Tracked... vehicles) {
        Map<String, Tracked> map = new LinkedHashMap<>();
        for (Tracked vehicle : vehicles) {
            map.put(vehicle.id(), vehicle);
        }
        return map;
    }

    /** One cycle: the delta against what was last sent, with the same fleet as the previous cycle. */
    private Delta cycle(Map<String, Tracked> lastSent, Map<String, Tracked> current) {
        return calculator.compute(lastSent, lastSent, current);
    }

    @Test
    void aNewVehicleIsSentWithNoPreviousPosition() {
        Delta delta = cycle(fleet(), fleet(tracked("a", "501", T0)));

        assertThat(delta.changed()).singleElement().satisfies(change -> {
            assertThat(change.now().id()).isEqualTo("a");
            assertThat(change.before()).isNull();
        });
    }

    @Test
    void aVehicleThatHasNotMovedIsNotSent() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        // Reported again 20 s later at the very same spot: a bus held at a light.
        Delta delta = cycle(before, fleet(tracked("a", "501", T0 + 20)));

        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void aVehicleThatMovedFarEnoughIsSentWithWhereItWas() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        Delta delta = cycle(before, fleet(tracked("a", "501", northMetres(40), LON, T0 + 20)));

        assertThat(delta.changed()).singleElement().satisfies(change -> {
            assertThat(change.now().lat()).isEqualTo(northMetres(40));
            // So a filter can tell whether the client had it before.
            assertThat(change.before().lat()).isEqualTo(LAT);
        });
    }

    @Test
    void movementBelowTheThresholdIsNotSentButAboveIsSent() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        assertThat(cycle(before, fleet(tracked("a", "501", northMetres(4.5), LON, T0 + 20))).isEmpty()).isTrue();
        assertThat(cycle(before, fleet(tracked("a", "501", northMetres(5.5), LON, T0 + 20))).changed()).hasSize(1);
    }

    @Test
    void creepingIsMeasuredFromWhereItWasLastSentSoItCannotDriftAway() {
        // Two metres a cycle never exceeds five metres in one step, but a client
        // holding the vehicle at its first position would be 6 m out by the third.
        Map<String, Tracked> sent = fleet(tracked("a", "501", T0));
        Map<String, Tracked> previousCycle = sent;
        int sentCount = 0;

        for (int cycle = 1; cycle <= 3; cycle++) {
            Map<String, Tracked> current = fleet(tracked("a", "501", northMetres(2.0 * cycle), LON, T0 + 10L * cycle));
            Delta delta = calculator.compute(sent, previousCycle, current);
            sentCount += delta.changed().size();
            sent = delta.nextSent();
            previousCycle = current;
        }

        // Ten seconds apart, so the keepalive cannot be what sends it: cycle 1 (2 m) and
        // cycle 2 (4 m) are held back, and cycle 3 (6 m from the original) is sent.
        assertThat(sentCount).isEqualTo(1);
        assertThat(sent.get("a").lat()).isEqualTo(northMetres(6));
    }

    @Test
    void theSameReadingDeliveredAgainIsNeverAChange() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        // Same timestamp, though the position differs: a republished reading, not news.
        Delta delta = cycle(before, fleet(tracked("a", "501", northMetres(100), LON, T0)));

        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void aReadingOlderThanTheOneSentIsIgnored() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        Delta delta = cycle(before, fleet(tracked("a", "501", northMetres(100), LON, T0 - 30)));

        assertThat(delta.isEmpty()).isTrue();
    }

    @Test
    void aStandingVehicleIsRefreshedAfterTheKeepaliveSoItDoesNotLookSilent() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        assertThat(cycle(before, fleet(tracked("a", "501", T0 + 59))).isEmpty()).isTrue();
        // Reporting every cycle at the same spot for a minute: the client would
        // otherwise see a timestamp that never advances and dim it as stale.
        assertThat(cycle(before, fleet(tracked("a", "501", T0 + 60))).changed()).hasSize(1);
    }

    @Test
    void aChangeOfRouteOrOccupancyIsSentEvenWithoutMovement() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0));

        Tracked newRoute = new Tracked("a", "504", LAT, LON, 90f, 5f, (short) 1, T0 + 20);
        Tracked newOccupancy = new Tracked("a", "501", LAT, LON, 90f, 5f, (short) 4, T0 + 20);

        assertThat(cycle(before, fleet(newRoute)).changed()).hasSize(1);
        assertThat(cycle(before, fleet(newOccupancy)).changed()).hasSize(1);
    }

    @Test
    void aVehicleThatStoppedReportingIsRemovedWithWhereItWasLastSeen() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0), tracked("b", "504", northMetres(300), LON, T0));

        Delta delta = cycle(before, fleet(tracked("a", "501", T0 + 20)));

        assertThat(delta.removed()).singleElement().satisfies(gone -> {
            assertThat(gone.id()).isEqualTo("b");
            assertThat(gone.lastSeen().route()).isEqualTo("504");
            assertThat(gone.lastSeen().lat()).isEqualTo(northMetres(300));
        });
        assertThat(delta.nextSent()).containsOnlyKeys("a");
    }

    @Test
    void whatWasSentIsExactlyTheCurrentFleetAfterwards() {
        Map<String, Tracked> before = fleet(tracked("a", "501", T0), tracked("gone", "501", T0));

        Delta delta = cycle(before, fleet(tracked("a", "501", T0 + 20), tracked("new", "504", T0 + 20)));

        assertThat(delta.nextSent()).containsOnlyKeys("a", "new");
    }

    @Test
    void anEmptyCycleAgainstAnEmptyPictureIsEmpty() {
        assertThat(cycle(fleet(), fleet()).isEmpty()).isTrue();
    }
}
