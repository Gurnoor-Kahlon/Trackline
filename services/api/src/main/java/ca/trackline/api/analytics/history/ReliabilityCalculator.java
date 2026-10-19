package ca.trackline.api.analytics.history;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import ca.trackline.api.analytics.history.ReliabilityModels.Coverage;
import ca.trackline.api.analytics.history.ReliabilityModels.Figures;
import ca.trackline.api.analytics.history.ReliabilityModels.HourOfDay;
import ca.trackline.api.analytics.history.ReliabilityModels.HourlyRollup;
import ca.trackline.api.analytics.history.ReliabilityModels.InsufficientReason;
import ca.trackline.api.analytics.history.ReliabilityModels.Reliability;
import ca.trackline.api.analytics.history.ReliabilityModels.ReliabilityStatus;
import ca.trackline.api.analytics.history.ReliabilityModels.Weekday;

/**
 * Turns a route's hourly summaries into reliability figures, or says that there is not enough of them.
 *
 * <p>Pure: no database, no clock. The gate is the point of it. Every figure is over what was actually
 * measured, and each is withheld unless enough was measured to stand behind it:
 *
 * <ul>
 *   <li>An hour counts only if it has enough samples. An hour the application ran for three minutes
 *       says nothing about that hour, and including it would let a handful of samples stand for sixty.
 *   <li>The route as a whole needs enough such hours, and enough samples that reached a verdict. A route
 *       always seen with one vehicle has been watched, not judged.
 *   <li>A bar of a breakdown needs enough samples and enough different days. One Tuesday evening does not
 *       show what evenings are like; with too few days the bar is absent, not drawn from what there is.
 * </ul>
 *
 * <p>Nothing is interpolated or smoothed. A figure is the count over the samples behind it.
 */
public final class ReliabilityCalculator {

    private final HistoryProperties.Reliability gate;
    private final ZoneId zone;

    /**
     * @param zone the time zone the day is read in. Whether 5 pm is rush hour is a fact about the city's
     *             clock, so the hours of the day and the weekdays are Toronto's
     */
    public ReliabilityCalculator(HistoryProperties.Reliability gate, ZoneId zone) {
        this.gate = gate;
        this.zone = zone;
    }

    public Reliability calculate(List<HourlyRollup> rollups) {
        if (rollups.isEmpty()) {
            return insufficient(InsufficientReason.NO_SAMPLES, new Coverage(0, 0, 0, 0, 0, null, null));
        }

        // An hour is covered or not as a whole route, over both directions.
        TreeMap<Instant, Integer> samplesByHour = new TreeMap<>();
        for (HourlyRollup rollup : rollups) {
            samplesByHour.merge(rollup.hourStart(), rollup.samples(), Integer::sum);
        }
        Set<Instant> covered = new HashSet<>();
        samplesByHour.forEach((hour, samples) -> {
            if (samples >= gate.minSamplesPerHour()) {
                covered.add(hour);
            }
        });

        Tally all = new Tally();
        Map<Integer, Cell> byHour = new TreeMap<>();
        Map<Integer, Cell> byWeekday = new TreeMap<>();
        Set<LocalDate> days = new HashSet<>();
        for (HourlyRollup rollup : rollups) {
            if (!covered.contains(rollup.hourStart())) {
                continue;
            }
            var local = rollup.hourStart().atZone(zone);
            LocalDate day = local.toLocalDate();
            days.add(day);
            all.add(rollup);
            byHour.computeIfAbsent(local.getHour(), key -> new Cell()).add(rollup, day);
            byWeekday.computeIfAbsent(local.getDayOfWeek().getValue(), key -> new Cell()).add(rollup, day);
        }

        Figures figures = all.figures();
        Coverage coverage = new Coverage(
                covered.size(),
                samplesByHour.size() - covered.size(),
                days.size(),
                figures.samples(),
                figures.judged(),
                samplesByHour.firstKey(),
                samplesByHour.lastKey());

        if (covered.size() < gate.minCoveredHours()) {
            return insufficient(InsufficientReason.TOO_FEW_HOURS, coverage);
        }
        if (figures.judged() < gate.minJudgedSamples()) {
            return insufficient(InsufficientReason.TOO_FEW_JUDGED_SAMPLES, coverage);
        }

        List<HourOfDay> hours = byHour.entrySet().stream()
                .filter(entry -> entry.getValue().stands(gate.minCellSamples(), gate.minDaysPerHourCell()))
                .map(entry -> new HourOfDay(entry.getKey(), entry.getValue().days.size(), entry.getValue().tally.figures()))
                .toList();
        List<Weekday> weekdays = byWeekday.entrySet().stream()
                .filter(entry -> entry.getValue().stands(gate.minCellSamples(), gate.minDaysPerWeekdayCell()))
                .map(entry -> new Weekday(entry.getKey(), entry.getValue().days.size(), entry.getValue().tally.figures()))
                .toList();

        return new Reliability(ReliabilityStatus.AVAILABLE, null, coverage, figures, hours, weekdays);
    }

    private static Reliability insufficient(InsufficientReason reason, Coverage coverage) {
        return new Reliability(ReliabilityStatus.INSUFFICIENT_HISTORY, reason, coverage, null, List.of(), List.of());
    }

    /** Counts and sums over a set of hourly rows. */
    private static final class Tally {
        private int samples;
        private int excellent;
        private int normal;
        private int unstable;
        private int major;
        private int unknown;
        private double variationSum;
        private int variationSamples;
        private double gapRatioSum;
        private int gapRatioSamples;
        private int bunching;

        void add(HourlyRollup rollup) {
            samples += rollup.samples();
            excellent += rollup.excellent();
            normal += rollup.normal();
            unstable += rollup.unstable();
            major += rollup.majorDisruption();
            unknown += rollup.unknown();
            variationSum += rollup.variationSum();
            variationSamples += rollup.variationSamples();
            gapRatioSum += rollup.gapRatioSum();
            gapRatioSamples += rollup.gapRatioSamples();
            bunching += rollup.bunchingSamples();
        }

        int judged() {
            return samples - unknown;
        }

        Figures figures() {
            int judged = judged();
            return new Figures(
                    samples,
                    judged,
                    excellent,
                    normal,
                    unstable,
                    major,
                    judged == 0 ? null : (double) (excellent + normal) / judged,
                    variationSamples == 0 ? null : variationSum / variationSamples,
                    gapRatioSamples == 0 ? null : gapRatioSum / gapRatioSamples,
                    judged == 0 ? null : (double) bunching / judged);
        }
    }

    /** A breakdown bar: the samples behind it and the different days they came from. */
    private static final class Cell {
        private final Tally tally = new Tally();
        private final Set<LocalDate> days = new HashSet<>();

        void add(HourlyRollup rollup, LocalDate day) {
            tally.add(rollup);
            days.add(day);
        }

        boolean stands(int minJudged, int minDays) {
            return tally.judged() >= minJudged && days.size() >= minDays;
        }
    }
}
