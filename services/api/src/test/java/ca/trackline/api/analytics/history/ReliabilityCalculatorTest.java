package ca.trackline.api.analytics.history;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.analytics.history.ReliabilityModels.HourOfDay;
import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;
import ca.trackline.api.analytics.history.ReliabilityModels.InsufficientReason;
import ca.trackline.api.analytics.history.ReliabilityModels.Reliability;
import ca.trackline.api.analytics.history.ReliabilityModels.ReliabilityStatus;
import ca.trackline.api.analytics.history.ReliabilityModels.Weekday;
import org.junit.jupiter.api.Test;

import static ca.trackline.api.analytics.history.HistoryFixtures.GATE;
import static ca.trackline.api.analytics.history.HistoryFixtures.TORONTO;
import static ca.trackline.api.analytics.history.HistoryFixtures.consecutiveHours;
import static ca.trackline.api.analytics.history.HistoryFixtures.hour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The reliability calculation, with the gate that stops it describing an afternoon as a pattern.
 *
 * <p>The cases are small and hand-counted. What matters most is what is withheld: a figure below the
 * minimum must not exist at all, and every boundary is checked on both sides.
 */
class ReliabilityCalculatorTest {

    /** Midnight at the start of Tuesday 13 October 2026, Toronto time (UTC-4). */
    private static final Instant TUESDAY = Instant.parse("2026-10-13T04:00:00Z");

    private final ReliabilityCalculator calculator = new ReliabilityCalculator(GATE, TORONTO);

    // --- nothing, or not enough --------------------------------------------------------------------------------

    @Test
    void aRouteWithNoSamplesIsInsufficientAndSaysSo() {
        Reliability result = calculator.calculate(List.of());

        assertThat(result.status()).isEqualTo(ReliabilityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.reason()).isEqualTo(InsufficientReason.NO_SAMPLES);
        assertThat(result.summary()).isNull();
        assertThat(result.byHour()).isEmpty();
        assertThat(result.byWeekday()).isEmpty();
        assertThat(result.coverage().firstHour()).isNull();
    }

    @Test
    void twentyThreeCoveredHoursIsOneShortOfTheMinimum() {
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 23, 20, 20, 10, 10));

        assertThat(result.status()).isEqualTo(ReliabilityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.reason()).isEqualTo(InsufficientReason.TOO_FEW_HOURS);
        assertThat(result.coverage().hoursCovered()).isEqualTo(23);
        // Withheld means absent, not zero: no figure of any kind comes back below the minimum.
        assertThat(result.summary()).isNull();
    }

    @Test
    void twentyFourCoveredHoursIsEnough() {
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 24, 20, 20, 10, 10));

        assertThat(result.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(result.reason()).isNull();
        assertThat(result.summary()).isNotNull();
    }

    @Test
    void anHourWithTooFewSamplesDoesNotCountAndIsReportedAsLeftOut() {
        List<HourlyRollup> rows = new ArrayList<>(consecutiveHours(TUESDAY, 23, 20, 20, 10, 10));
        // Nineteen samples: the application was running for most of the hour, not all of it.
        rows.add(hour(TUESDAY.plus(Duration.ofHours(23))).verdicts(19, 0, 0, 0).build());

        Reliability result = calculator.calculate(rows);

        assertThat(result.reason()).isEqualTo(InsufficientReason.TOO_FEW_HOURS);
        assertThat(result.coverage().hoursCovered()).isEqualTo(23);
        assertThat(result.coverage().hoursExcluded()).isEqualTo(1);
    }

    @Test
    void anHourWithExactlyTheMinimumSamplesCounts() {
        List<HourlyRollup> rows = new ArrayList<>(consecutiveHours(TUESDAY, 23, 20, 20, 10, 10));
        rows.add(hour(TUESDAY.plus(Duration.ofHours(23))).verdicts(20, 0, 0, 0).build());

        Reliability result = calculator.calculate(rows);

        assertThat(result.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(result.coverage().hoursExcluded()).isZero();
    }

    @Test
    void anExcludedHourContributesToNoFigure() {
        List<HourlyRollup> rows = new ArrayList<>(consecutiveHours(TUESDAY, 24, 20, 20, 10, 10));
        // A thin hour of unstable service must not move the share: it has too few samples to be described.
        rows.add(hour(TUESDAY.plus(Duration.ofHours(24))).verdicts(0, 0, 5, 5).build());

        Reliability result = calculator.calculate(rows);

        assertThat(result.summary().samples()).isEqualTo(24 * 60);
        assertThat(result.summary().unstable()).isEqualTo(24 * 10);
        assertThat(result.coverage().hoursExcluded()).isEqualTo(1);
    }

    @Test
    void anHoursSamplesAreCountedOverBothDirections() {
        // Ten samples in each direction: twenty for the route, which is enough to cover the hour.
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            Instant start = TUESDAY.plus(Duration.ofHours(index));
            rows.add(hour(start).direction(0).verdicts(10, 0, 0, 0).build());
            rows.add(hour(start).direction(1).verdicts(10, 0, 0, 0).build());
        }

        Reliability result = calculator.calculate(rows);

        assertThat(result.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(result.summary().samples()).isEqualTo(480);
    }

    @Test
    void aRouteSeenButNeverJudgedIsInsufficientForThatReason() {
        // Twenty unknown samples an hour for a day: watched, never judged, as for a route with one vehicle.
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            rows.add(hour(TUESDAY.plus(Duration.ofHours(index))).unknown(20).build());
        }

        Reliability result = calculator.calculate(rows);

        assertThat(result.reason()).isEqualTo(InsufficientReason.TOO_FEW_JUDGED_SAMPLES);
        assertThat(result.coverage().judgedSamples()).isZero();
        assertThat(result.coverage().samples()).isEqualTo(480);
        assertThat(result.summary()).isNull();
    }

    @Test
    void twoHundredAndFortyJudgedSamplesIsEnoughAndTwoHundredAndThirtyNineIsNot() {
        List<HourlyRollup> enough = new ArrayList<>();
        List<HourlyRollup> oneShort = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            Instant start = TUESDAY.plus(Duration.ofHours(index));
            enough.add(hour(start).verdicts(10, 0, 0, 0).unknown(10).build());
            oneShort.add(hour(start).verdicts(index == 0 ? 9 : 10, 0, 0, 0).unknown(index == 0 ? 11 : 10).build());
        }

        assertThat(calculator.calculate(enough).status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        Reliability short239 = calculator.calculate(oneShort);
        assertThat(short239.coverage().judgedSamples()).isEqualTo(239);
        assertThat(short239.reason()).isEqualTo(InsufficientReason.TOO_FEW_JUDGED_SAMPLES);
    }

    @Test
    void anInsufficientAnswerStillReportsWhatThereIsSoTheReaderSeesHowFarOffItIs() {
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 9, 20, 20, 10, 10));

        assertThat(result.coverage().hoursCovered()).isEqualTo(9);
        assertThat(result.coverage().samples()).isEqualTo(9 * 60);
        assertThat(result.coverage().days()).isEqualTo(1);
        assertThat(result.coverage().firstHour()).isEqualTo(TUESDAY);
        assertThat(result.coverage().lastHour()).isEqualTo(TUESDAY.plus(Duration.ofHours(8)));
    }

    // --- the figures -------------------------------------------------------------------------------------------

    @Test
    void sharesAreCountsOverTheSamplesBehindThem() {
        // Per hour: 20 excellent, 20 normal, 10 unstable, 10 major disruption.
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 24, 20, 20, 10, 10));

        assertThat(result.summary().samples()).isEqualTo(1440);
        assertThat(result.summary().judged()).isEqualTo(1440);
        assertThat(result.summary().excellent()).isEqualTo(480);
        assertThat(result.summary().normal()).isEqualTo(480);
        assertThat(result.summary().unstable()).isEqualTo(240);
        assertThat(result.summary().majorDisruption()).isEqualTo(240);
        // Excellent and normal are the steady ones: 960 of 1440.
        assertThat(result.summary().steadyShare()).isCloseTo(960.0 / 1440.0, within(1e-12));
        assertThat(result.summary().variation()).isCloseTo(0.3, within(1e-12));
        assertThat(result.summary().gapRatio()).isCloseTo(1.2, within(1e-12));
        assertThat(result.summary().bunchingShare()).isZero();
    }

    @Test
    void unknownSamplesAreLeftOutOfEveryShareAndReportedOnTheirOwn() {
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            rows.add(hour(TUESDAY.plus(Duration.ofHours(index))).verdicts(10, 0, 0, 0).unknown(10).build());
        }

        Reliability result = calculator.calculate(rows);

        assertThat(result.summary().samples()).isEqualTo(480);
        assertThat(result.summary().judged()).isEqualTo(240);
        assertThat(result.summary().unjudged()).isEqualTo(240);
        // Judged samples were all excellent: the unjudged half must not dilute that to 50 %.
        assertThat(result.summary().steadyShare()).isEqualTo(1.0);
    }

    @Test
    void theMeanVariationIsWeightedBySamplesNotAveragedAcrossHours() {
        List<HourlyRollup> rows = new ArrayList<>();
        // 100 samples at a mean variation of 0.2, then 20 at 0.5, then 22 hours with no variation to report.
        rows.add(hour(TUESDAY).verdicts(100, 0, 0, 0).variationTotals(20.0, 100).build());
        rows.add(hour(TUESDAY.plus(Duration.ofHours(1))).verdicts(20, 0, 0, 0).variationTotals(10.0, 20).build());
        for (int index = 2; index < 24; index++) {
            rows.add(hour(TUESDAY.plus(Duration.ofHours(index))).verdicts(20, 0, 0, 0).build());
        }

        Reliability result = calculator.calculate(rows);

        // 30 over 120 samples, not the 0.35 an average of the two hourly means would give.
        assertThat(result.summary().variation()).isCloseTo(0.25, within(1e-12));
    }

    @Test
    void aFigureNobodyMeasuredIsNullAndNotZero() {
        // Twenty-four covered hours, judged, but no sample had a variation or a gap ratio to contribute.
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            rows.add(hour(TUESDAY.plus(Duration.ofHours(index))).verdicts(20, 0, 0, 0).build());
        }

        Reliability result = calculator.calculate(rows);

        assertThat(result.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(result.summary().variation()).isNull();
        assertThat(result.summary().gapRatio()).isNull();
    }

    @Test
    void theBunchingShareIsOverJudgedSamples() {
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            // Twelve of the twenty judged samples each hour had a bunch in them.
            rows.add(hour(TUESDAY.plus(Duration.ofHours(index))).verdicts(0, 0, 20, 0).unknown(10).bunching(12).build());
        }

        Reliability result = calculator.calculate(rows);

        assertThat(result.summary().bunchingShare()).isCloseTo(0.6, within(1e-12));
    }

    // --- breakdowns, and what they need -----------------------------------------------------------------------

    @Test
    void oneDayOfSamplesDrawsNoHourOfDayBarsBecauseOneDayIsAnAnecdote() {
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 24, 20, 20, 10, 10));

        assertThat(result.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(result.coverage().days()).isEqualTo(1);
        assertThat(result.byHour()).isEmpty();
        assertThat(result.byWeekday()).isEmpty();
    }

    @Test
    void anHourOfTheDayAppearsOnceThreeDaysStandBehindIt() {
        // Three days of every hour: three different days behind each hour of the day.
        Reliability three = calculator.calculate(consecutiveHours(TUESDAY, 72, 20, 20, 10, 10));
        // Two days: one short, so no hour has enough to be drawn even though the route as a whole does.
        Reliability two = calculator.calculate(consecutiveHours(TUESDAY, 48, 20, 20, 10, 10));

        assertThat(three.byHour()).hasSize(24);
        assertThat(three.byHour()).allSatisfy(cell -> assertThat(cell.days()).isEqualTo(3));
        assertThat(two.status()).isEqualTo(ReliabilityStatus.AVAILABLE);
        assertThat(two.byHour()).isEmpty();
    }

    @Test
    void anHourOfTheDayNeedsEnoughJudgedSamplesAsWellAsEnoughDays() {
        // Local 18:00 is 22:00 UTC. Over three days its three rows are covered hours either way, with twenty samples
        // each; what differs is how many of those reached a verdict.
        List<Instant> evenings = List.of(
                Instant.parse("2026-10-13T22:00:00Z"),
                Instant.parse("2026-10-14T22:00:00Z"),
                Instant.parse("2026-10-15T22:00:00Z"));

        // 60 judged samples across the three days is exactly the minimum for a bar.
        List<HourlyRollup> exactly = new ArrayList<>(consecutiveHours(TUESDAY, 72, 20, 20, 10, 10));
        exactly.replaceAll(row -> evenings.contains(row.hourStart()) ? hour(row.hourStart()).verdicts(20, 0, 0, 0).build() : row);
        // Half of each evening's samples unjudged leaves 30: three days, but too little behind them.
        List<HourlyRollup> thin = new ArrayList<>(consecutiveHours(TUESDAY, 72, 20, 20, 10, 10));
        thin.replaceAll(row -> evenings.contains(row.hourStart())
                ? hour(row.hourStart()).verdicts(10, 0, 0, 0).unknown(10).build()
                : row);

        assertThat(hours(calculator.calculate(exactly))).contains(18).hasSize(24);
        assertThat(hours(calculator.calculate(thin))).doesNotContain(18).hasSize(23);
    }

    @Test
    void hoursOfTheDayAreReadOnTheTorontoClockInSummer() {
        // 22:00 UTC in October is 18:00 in Toronto (UTC-4).
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 96, 20, 20, 10, 10));

        HourOfDay evening = result.byHour().stream().filter(cell -> cell.hour() == 18).findFirst().orElseThrow();
        assertThat(evening.days()).isEqualTo(4);
        assertThat(evening.figures().samples()).isEqualTo(4 * 60);
    }

    @Test
    void hoursOfTheDayAreReadOnTheTorontoClockInWinter() {
        // 05:00 UTC in November, after the clocks go back, is midnight in Toronto (UTC-5).
        Instant winterMidnight = Instant.parse("2026-11-10T05:00:00Z");
        Reliability result = calculator.calculate(consecutiveHours(winterMidnight, 96, 20, 20, 10, 10));

        assertThat(result.byHour().get(0).hour()).isEqualTo(0);
        assertThat(result.byHour().get(0).days()).isEqualTo(4);
        assertThat(result.byHour()).hasSize(24);
    }

    @Test
    void aWeekdayNeedsTwoDifferentDaysSoItAppearsOnlyAfterAWeek() {
        // Eight days from Tuesday midnight: Tuesday occurs twice, every other weekday once.
        Reliability result = calculator.calculate(consecutiveHours(TUESDAY, 8 * 24, 20, 20, 10, 10));

        assertThat(result.byWeekday()).extracting(Weekday::isoWeekday).containsExactly(2);
        assertThat(result.byWeekday().get(0).days()).isEqualTo(2);
        assertThat(result.byHour()).hasSize(24);
    }

    @Test
    void weekdaysAreReadOnTheTorontoClockNotUtc() {
        // Starts at 00:00 UTC on Tuesday, which is 20:00 Monday in Toronto. Read in UTC these first hours
        // would be Tuesday's, and Monday would never reach two days.
        Instant mondayEvening = Instant.parse("2026-10-13T00:00:00Z");
        Reliability result = calculator.calculate(consecutiveHours(mondayEvening, 8 * 24, 20, 20, 10, 10));

        assertThat(result.byWeekday()).extracting(Weekday::isoWeekday).containsExactly(1, 2);
        Weekday monday = result.byWeekday().get(0);
        // The evening of Monday 12 October (four hours) and all of Monday 19 October.
        assertThat(monday.days()).isEqualTo(2);
        assertThat(monday.figures().samples()).isEqualTo((4 + 24) * 60);
    }

    @Test
    void aHourOfTheDayIsDrawnOnlyFromCoveredHours() {
        // Three days, with the 09:00 hour of the third day thin: it must not count towards a third day.
        List<HourlyRollup> rows = new ArrayList<>(consecutiveHours(TUESDAY, 72, 20, 20, 10, 10));
        Instant nineOnThursday = Instant.parse("2026-10-15T13:00:00Z");
        rows.replaceAll(row -> row.hourStart().equals(nineOnThursday)
                ? hour(row.hourStart()).verdicts(5, 0, 0, 0).build()
                : row);

        Reliability result = calculator.calculate(rows);

        assertThat(hours(result)).doesNotContain(9);
        assertThat(hours(result)).hasSize(23);
    }

    private static List<Integer> hours(Reliability result) {
        return result.byHour().stream().map(HourOfDay::hour).toList();
    }
}
