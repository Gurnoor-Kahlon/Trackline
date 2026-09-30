package ca.trackline.api.gtfs.schedule;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The field-level rules. These are where an import quietly corrupts data rather
 * than failing, so they are tested directly.
 */
class GtfsValuesTest {

    @Test
    void timesConvertToSecondsAfterMidnight() {
        assertThat(GtfsValues.parseTimeToSeconds("00:00:00", "t")).isZero();
        assertThat(GtfsValues.parseTimeToSeconds("18:15:56", "t")).isEqualTo(65_756);
        assertThat(GtfsValues.parseTimeToSeconds("23:59:59", "t")).isEqualTo(86_399);
    }

    @Test
    void timesPastMidnightKeepCounting() {
        // A trip leaving at 25:15:00 is the 01:15 departure of the previous
        // service day. Parsed as a time of day it would be rejected or wrap to
        // 01:15 and land on the wrong day.
        assertThat(GtfsValues.parseTimeToSeconds("24:00:00", "t")).isEqualTo(86_400);
        assertThat(GtfsValues.parseTimeToSeconds("25:15:00", "t")).isEqualTo(90_900);
        assertThat(GtfsValues.parseTimeToSeconds("28:30:15", "t")).isEqualTo(102_615);
    }

    @Test
    void singleDigitHoursAreAccepted() {
        assertThat(GtfsValues.parseTimeToSeconds("5:07:03", "t")).isEqualTo(18_423);
    }

    @Test
    void malformedTimesAreRejectedRatherThanGuessed() {
        assertThatThrownBy(() -> GtfsValues.parseTimeToSeconds("12:30", "arrival_time"))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("HH:MM:SS");
        assertThatThrownBy(() -> GtfsValues.parseTimeToSeconds("12:75:00", "arrival_time"))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> GtfsValues.parseTimeToSeconds("ab:cd:ef", "arrival_time"))
                .isInstanceOf(GtfsFormatException.class);
        assertThatThrownBy(() -> GtfsValues.parseTimeToSeconds("", "arrival_time"))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("required");
    }

    @Test
    void optionalDecimalsDistinguishAbsentFromZero() {
        // The TTC leaves shape_dist_traveled empty on the first stop of many
        // trips. Defaulting it to 0 would claim the vehicle starts at distance
        // zero along a shape, which is a measurement we were never given.
        assertThat(GtfsValues.optionalDecimal("", "d")).isNull();
        assertThat(GtfsValues.optionalDecimal("   ", "d")).isNull();
        assertThat(GtfsValues.optionalDecimal(null, "d")).isNull();
        assertThat(GtfsValues.optionalDecimal("0.0000", "d")).isEqualTo("0.0000");
        assertThat(GtfsValues.optionalDecimal("0.2840", "d")).isEqualTo("0.2840");
    }

    @Test
    void optionalDecimalRejectsNonNumericText() {
        assertThatThrownBy(() -> GtfsValues.optionalDecimal("far", "shape_dist_traveled"))
                .isInstanceOf(GtfsFormatException.class);
    }

    @Test
    void datesParseFromTheGtfsFormat() {
        assertThat(GtfsValues.parseDate("20260930", "start_date")).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> GtfsValues.parseDate("2026-09-30", "start_date"))
                .isInstanceOf(GtfsFormatException.class);
    }

    @Test
    void booleansAreTheCharactersZeroAndOne() {
        assertThat(GtfsValues.parseBoolean("1", "monday")).isTrue();
        assertThat(GtfsValues.parseBoolean("0", "monday")).isFalse();
        assertThatThrownBy(() -> GtfsValues.parseBoolean("true", "monday"))
                .isInstanceOf(GtfsFormatException.class);
    }

    @Test
    void absentAccessibilityDefaultsToNotStated() {
        // GTFS 0 means "no information", which is exactly what an empty field
        // conveys. Anything else would invent accessibility data.
        assertThat(GtfsValues.parseIntOrDefault("", 0, "wheelchair_boarding")).isZero();
        assertThat(GtfsValues.parseIntOrDefault("1", 0, "wheelchair_boarding")).isEqualTo(1);
        assertThat(GtfsValues.parseIntOrDefault("2", 0, "wheelchair_boarding")).isEqualTo(2);
    }

    @Test
    void unusableColoursBecomeNullInsteadOfFailingTheImport() {
        assertThat(GtfsValues.optionalHexColour("DA251D")).isEqualTo("DA251D");
        assertThat(GtfsValues.optionalHexColour("")).isNull();
        assertThat(GtfsValues.optionalHexColour("#DA251D")).isNull();
        assertThat(GtfsValues.optionalHexColour("DA251")).isNull();
        assertThat(GtfsValues.optionalHexColour("ZZZZZZ")).isNull();
    }

    @Test
    void realtimeSupportIsDerivedFromTheMode() {
        // The TTC publishes no subway vehicle positions, so route_type 1 never
        // has live vehicles however the feed is shaped.
        assertThat(GtfsValues.supportsRealtime(3)).isTrue();
        assertThat(GtfsValues.supportsRealtime(0)).isTrue();
        assertThat(GtfsValues.supportsRealtime(1)).isFalse();
    }

    @Test
    void requiredTextIsTrimmedAndEmptyIsRejected() {
        assertThat(GtfsValues.requireText("  501  ", "route_id")).isEqualTo("501");
        assertThat(GtfsValues.optionalText("   ")).isNull();
        assertThatThrownBy(() -> GtfsValues.requireText("  ", "route_id"))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("route_id");
    }
}
