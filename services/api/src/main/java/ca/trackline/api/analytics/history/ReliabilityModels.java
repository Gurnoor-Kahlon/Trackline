package ca.trackline.api.analytics.history;

import java.time.Instant;
import java.util.List;

/**
 * The value types of the reliability calculation: the hourly summaries it reads and the figures it
 * returns. Plain records, shared by the pure calculator, the query that feeds it and the API.
 */
public final class ReliabilityModels {

    private ReliabilityModels() {
    }

    /**
     * One route direction for one hour, as stored: how many samples there were, how many reached each verdict,
     * and sums that allow an exact mean over any set of hours.
     *
     * @param samples          samples taken in the hour; the sum of the five verdict counts
     * @param variationSum     sum of the headway variation over the samples that had one
     * @param variationSamples how many samples that was
     * @param gapRatioSum      sum of the largest-gap ratio over the samples that had one
     * @param gapRatioSamples  how many samples that was
     * @param bunchingSamples  samples in which at least one bunch was found
     */
    public record HourlyRollup(
            String routeId,
            short directionId,
            Instant hourStart,
            int samples,
            int excellent,
            int normal,
            int unstable,
            int majorDisruption,
            int unknown,
            double variationSum,
            int variationSamples,
            double gapRatioSum,
            int gapRatioSamples,
            int bunchingSamples) {
    }

    /** Whether there is enough history to say anything. */
    public enum ReliabilityStatus {
        AVAILABLE,
        INSUFFICIENT_HISTORY
    }

    /** Why there is not enough history, in the order the checks are made. */
    public enum InsufficientReason {
        /**
         * No summarised hour in the window. The route may never have been recorded, or may have been recorded only
         * since the last hour finished: an hour is summarised once it has ended.
         */
        NO_SAMPLES,
        /** Seen, but for too few hours with enough samples to describe them. */
        TOO_FEW_HOURS,
        /** Seen for long enough, but too few samples reached a verdict. */
        TOO_FEW_JUDGED_SAMPLES
    }

    /**
     * What a set of samples shows. Every figure is over samples, and a sample is one route direction at one
     * moment, so a route running both ways counts twice a minute.
     *
     * @param samples          every sample in the set
     * @param judged           samples that reached a verdict, which is every sample but the unknown ones
     * @param steadyShare      judged samples that were {@code EXCELLENT} or {@code NORMAL} over judged
     *                         samples; null when none were judged
     * @param variation        mean coefficient of variation of the gaps, over the samples that had one; null when none did
     * @param gapRatio         mean ratio of the largest gap to the expected spacing; null when none had one
     * @param bunchingShare    judged samples with at least one bunch over judged samples; null when none were judged
     */
    public record Figures(
            int samples,
            int judged,
            int excellent,
            int normal,
            int unstable,
            int majorDisruption,
            Double steadyShare,
            Double variation,
            Double gapRatio,
            Double bunchingShare) {

        public int unjudged() {
            return samples - judged;
        }
    }

    /**
     * One hour of the Toronto day, over every day in the window that had it covered.
     *
     * @param hour 0 to 23, Toronto time
     * @param days how many different days it is drawn from
     */
    public record HourOfDay(int hour, int days, Figures figures) {
    }

    /**
     * One day of the week, over every such day in the window that had any covered hours.
     *
     * @param isoWeekday 1 for Monday to 7 for Sunday
     * @param days       how many different days it is drawn from
     */
    public record Weekday(int isoWeekday, int days, Figures figures) {
    }

    /**
     * How much history stands behind a figure.
     *
     * @param hoursCovered   hours with enough samples to describe them
     * @param hoursExcluded  hours with some samples but too few, left out of every figure
     * @param days           different Toronto days the covered hours fall on
     * @param samples        samples in the covered hours
     * @param judgedSamples  of those, the ones that reached a verdict
     * @param firstHour      start of the earliest hour with any sample, or null when there is none
     * @param lastHour       start of the latest, or null
     */
    public record Coverage(
            int hoursCovered,
            int hoursExcluded,
            int days,
            int samples,
            int judgedSamples,
            Instant firstHour,
            Instant lastHour) {
    }

    /**
     * The result: either figures, or the reason there are none.
     *
     * @param reason  null when the status is {@code AVAILABLE}
     * @param summary null unless {@code AVAILABLE}
     * @param byHour  the hours of the day that have enough behind them; others are absent, not zero
     * @param byWeekday the weekdays that have enough behind them
     */
    public record Reliability(
            ReliabilityStatus status,
            InsufficientReason reason,
            Coverage coverage,
            Figures summary,
            List<HourOfDay> byHour,
            List<Weekday> byWeekday) {

        public Reliability {
            byHour = List.copyOf(byHour);
            byWeekday = List.copyOf(byWeekday);
        }
    }
}
