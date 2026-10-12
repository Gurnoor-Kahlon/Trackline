package ca.trackline.api.alerts;

import java.util.List;

import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint decides whether an alert counts as edited, so what it ignores and
 * what it notices both matter: a hash that moved on a reordered list would record an
 * edit that never happened, and one that missed a changed word would never record a
 * real one.
 */
class AlertContentHashTest {

    private static final AffectedEntity ROUTE_501 = new AffectedEntity("501", null);
    private static final AffectedEntity ROUTE_504 = new AffectedEntity("504", null);

    private static AlertSnapshot alert(String header, String description, List<AffectedEntity> affected) {
        return new AlertSnapshot("alert-1", 1, 8, header, description, null, affected);
    }

    private static String hash(AlertSnapshot alert) {
        return AlertContentHash.of(alert);
    }

    @Test
    void isASha256InHex() {
        assertThat(hash(alert("h", "d", List.of(ROUTE_501)))).matches("^[0-9a-f]{64}$");
    }

    @Test
    void isTheSameForTheSameContent() {
        assertThat(hash(alert("h", "d", List.of(ROUTE_501)))).isEqualTo(hash(alert("h", "d", List.of(ROUTE_501))));
    }

    @Test
    void ignoresTheOrderOfTheAffectedEntities() {
        assertThat(hash(alert("h", "d", List.of(ROUTE_501, ROUTE_504))))
                .isEqualTo(hash(alert("h", "d", List.of(ROUTE_504, ROUTE_501))));
    }

    @Test
    void ignoresAnEntityRepeatedWithinTheAlert() {
        // The real feed does this: one alert listed the same route and stop three times.
        assertThat(hash(alert("h", "d", List.of(ROUTE_501, ROUTE_501, ROUTE_501))))
                .isEqualTo(hash(alert("h", "d", List.of(ROUTE_501))));
    }

    @Test
    void ignoresEntitiesThatNameNeitherARouteNorAStop() {
        // They are not stored, so their appearance or disappearance is not a change.
        assertThat(hash(alert("h", "d", List.of(ROUTE_501, new AffectedEntity(null, null)))))
                .isEqualTo(hash(alert("h", "d", List.of(ROUTE_501))));
    }

    @Test
    void ignoresThePublishersIdentifier() {
        AlertSnapshot first = new AlertSnapshot("alert-1", 1, 8, "h", "d", null, List.of(ROUTE_501));
        AlertSnapshot second = new AlertSnapshot("alert-2", 1, 8, "h", "d", null, List.of(ROUTE_501));

        // It says which alert this is, not what the alert says.
        assertThat(hash(first)).isEqualTo(hash(second));
    }

    @Test
    void noticesAChangeInEachVisibleField() {
        String baseline = hash(new AlertSnapshot("a", 1, 8, "h", "d", "u", List.of(ROUTE_501)));

        assertThat(hash(new AlertSnapshot("a", 2, 8, "h", "d", "u", List.of(ROUTE_501)))).isNotEqualTo(baseline);
        assertThat(hash(new AlertSnapshot("a", 1, 4, "h", "d", "u", List.of(ROUTE_501)))).isNotEqualTo(baseline);
        assertThat(hash(new AlertSnapshot("a", 1, 8, "H", "d", "u", List.of(ROUTE_501)))).isNotEqualTo(baseline);
        assertThat(hash(new AlertSnapshot("a", 1, 8, "h", "D", "u", List.of(ROUTE_501)))).isNotEqualTo(baseline);
        assertThat(hash(new AlertSnapshot("a", 1, 8, "h", "d", "U", List.of(ROUTE_501)))).isNotEqualTo(baseline);
        assertThat(hash(new AlertSnapshot("a", 1, 8, "h", "d", "u", List.of(ROUTE_501, ROUTE_504))))
                .isNotEqualTo(baseline);
    }

    @Test
    void noticesAStopBeingAddedToARoute() {
        assertThat(hash(alert("h", "d", List.of(new AffectedEntity("501", "100")))))
                .isNotEqualTo(hash(alert("h", "d", List.of(ROUTE_501))));
    }

    @Test
    void doesNotConfuseARouteWithAStopOfTheSameId() {
        assertThat(hash(alert("h", "d", List.of(new AffectedEntity("7", null)))))
                .isNotEqualTo(hash(alert("h", "d", List.of(new AffectedEntity(null, "7")))));
    }

    @Test
    void doesNotLetTextMoveAcrossAFieldBoundary() {
        // Without length prefixes, "ab" + "c" and "a" + "bc" would be the same bytes.
        assertThat(hash(alert("ab", "c", List.of(ROUTE_501)))).isNotEqualTo(hash(alert("a", "bc", List.of(ROUTE_501))));
    }

    @Test
    void distinguishesNoTextFromEmptyText() {
        assertThat(hash(alert(null, "d", List.of(ROUTE_501)))).isNotEqualTo(hash(alert("", "d", List.of(ROUTE_501))));
    }
}
