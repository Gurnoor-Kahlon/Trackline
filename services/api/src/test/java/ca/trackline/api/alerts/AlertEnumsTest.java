package ca.trackline.api.alerts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers are the GTFS-Realtime specification's, so a mistake here would label
 * every alert wrongly and nothing else would notice.
 */
class AlertEnumsTest {

    @Test
    void theNumbersTheTtcActuallyPublishesAreTheUnknownValues() {
        // Every alert measured on the live feed carries cause 1 and effect 8.
        assertThat(AlertCause.fromNumber(1)).isEqualTo(AlertCause.UNKNOWN_CAUSE);
        assertThat(AlertEffect.fromNumber(8)).isEqualTo(AlertEffect.UNKNOWN_EFFECT);
    }

    @Test
    void mapsTheSpecificationsOtherValues() {
        assertThat(AlertCause.fromNumber(10)).isEqualTo(AlertCause.CONSTRUCTION);
        assertThat(AlertCause.fromNumber(12)).isEqualTo(AlertCause.MEDICAL_EMERGENCY);
        assertThat(AlertEffect.fromNumber(1)).isEqualTo(AlertEffect.NO_SERVICE);
        assertThat(AlertEffect.fromNumber(4)).isEqualTo(AlertEffect.DETOUR);
        assertThat(AlertEffect.fromNumber(11)).isEqualTo(AlertEffect.ACCESSIBILITY_ISSUE);
    }

    @Test
    void everyNumberMapsBackToItself() {
        for (AlertEffect effect : AlertEffect.values()) {
            assertThat(AlertEffect.fromNumber(effect.ordinal() + 1)).isEqualTo(effect);
        }
        for (AlertCause cause : AlertCause.values()) {
            assertThat(AlertCause.fromNumber(cause.ordinal() + 1)).isEqualTo(cause);
        }
    }

    @Test
    void anAbsentOrUndefinedNumberIsNullNotAGuess() {
        assertThat(AlertCause.fromNumber(null)).isNull();
        assertThat(AlertEffect.fromNumber(null)).isNull();
        assertThat(AlertCause.fromNumber(0)).isNull();
        assertThat(AlertEffect.fromNumber(99)).isNull();
    }
}
