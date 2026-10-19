package ca.trackline.api.analytics.history;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;

/**
 * Shared test data for the history and reliability tests.
 */
final class HistoryFixtures {

    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    /** The shipped gate, so the tests describe the behaviour that ships. */
    static final HistoryProperties.Reliability GATE = new HistoryProperties.Reliability(24, 20, 240, 60, 3, 2);

    private HistoryFixtures() {
    }

    static HistoryProperties properties() {
        return new HistoryProperties(
                false,
                Duration.ofSeconds(60),
                Duration.ofMinutes(10),
                Duration.ofHours(6),
                Duration.ofMinutes(2),
                new HistoryProperties.Retention(
                        Duration.ofDays(90), Duration.ofDays(365), Duration.ofDays(14),
                        Duration.ofDays(180), Duration.ofDays(180), Duration.ofDays(30)),
                GATE);
    }

    /** One hourly row, described by the verdicts its samples reached. */
    static Hour hour(Instant start) {
        return new Hour(start);
    }

    static final class Hour {
        private final Instant start;
        private String route = "501";
        private int direction;
        private int excellent;
        private int normal;
        private int unstable;
        private int major;
        private int unknown;
        private Double variationSum;
        private Integer variationSamples;
        private double gapRatioSum;
        private int gapRatioSamples;
        private int bunching;

        private Hour(Instant start) {
            this.start = start;
        }

        Hour route(String value) {
            route = value;
            return this;
        }

        Hour direction(int value) {
            direction = value;
            return this;
        }

        Hour verdicts(int excellentCount, int normalCount, int unstableCount, int majorCount) {
            excellent = excellentCount;
            normal = normalCount;
            unstable = unstableCount;
            major = majorCount;
            return this;
        }

        Hour unknown(int count) {
            unknown = count;
            return this;
        }

        /** Every judged sample had a variation, each of this value. */
        Hour variation(double each) {
            variationSamples = judged();
            variationSum = each * judged();
            return this;
        }

        /** The sum and count directly, for the weighting tests. */
        Hour variationTotals(double sum, int samples) {
            variationSum = sum;
            variationSamples = samples;
            return this;
        }

        Hour gapRatio(double each) {
            gapRatioSamples = judged();
            gapRatioSum = each * judged();
            return this;
        }

        Hour bunching(int samples) {
            bunching = samples;
            return this;
        }

        private int judged() {
            return excellent + normal + unstable + major;
        }

        HourlyRollup build() {
            int samples = judged() + unknown;
            return new HourlyRollup(
                    route, (short) direction, start, samples, excellent, normal, unstable, major, unknown,
                    variationSum == null ? 0 : variationSum, variationSamples == null ? 0 : variationSamples,
                    gapRatioSum, gapRatioSamples, bunching);
        }
    }

    /**
     * The same hour of consecutive days, each a covered hour of steady service. The first is at the given start.
     */
    static List<HourlyRollup> everyDayAt(Instant firstStart, int days, int samplesPerHour) {
        List<HourlyRollup> rows = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            rows.add(hour(firstStart.plus(Duration.ofDays(day))).verdicts(samplesPerHour, 0, 0, 0).variation(0.2).build());
        }
        return rows;
    }

    /** Consecutive hours from a start, each with the same make-up. */
    static List<HourlyRollup> consecutiveHours(Instant firstStart, int hours, int excellent, int normal, int unstable, int major) {
        List<HourlyRollup> rows = new ArrayList<>();
        for (int index = 0; index < hours; index++) {
            rows.add(hour(firstStart.plus(Duration.ofHours(index)))
                    .verdicts(excellent, normal, unstable, major).variation(0.3).gapRatio(1.2).build());
        }
        return rows;
    }
}
