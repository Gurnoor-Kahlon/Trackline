package ca.trackline.api.analytics.history;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import ca.trackline.api.analytics.history.ReliabilityModels.Coverage;
import ca.trackline.api.analytics.history.ReliabilityModels.Figures;
import ca.trackline.api.analytics.history.ReliabilityModels.InsufficientReason;
import ca.trackline.api.analytics.history.ReliabilityModels.Reliability;
import ca.trackline.api.analytics.history.ReliabilityModels.ReliabilityStatus;

/**
 * Shapes returned by the reliability endpoint.
 *
 * <p>Every answer says how much history stands behind it, and when there is not enough it says how much
 * is needed and how much there is, so a reader sees "9 of 24 hours" and not an empty chart with no
 * reason. Shares are fractions (0.62 is 62 %), rounded to a tenth of a percent; the figures carry the
 * counts they were computed from, so a share can be checked.
 */
public final class ReliabilityDtos {

    private ReliabilityDtos() {
    }

    /**
     * @param status    whether there is enough history to show figures
     * @param reason    why not, when there is not
     * @param windowDays the window asked for
     * @param from      the start of the window
     * @param through   the end of the latest hour with any sample in it, or null when there is none.
     *                  Hours are summarised once finished, so this lags the present by up to an hour
     *                  and a few minutes
     * @param timeZone  the zone the hours of the day and the weekdays are read in
     * @param needs     what a figure needs before it is shown
     * @param summary   the figures over the whole window; null unless available
     * @param byHour    hours of the day with enough behind them; an hour that is absent had too little
     * @param byWeekday the same for days of the week
     */
    public record RouteReliabilityView(
            String routeId,
            ReliabilityStatus status,
            InsufficientReason reason,
            int windowDays,
            Instant from,
            Instant through,
            String timeZone,
            Needs needs,
            CoverageView coverage,
            FiguresView summary,
            List<HourView> byHour,
            List<WeekdayView> byWeekday) {

        static RouteReliabilityView of(
                String routeId,
                Reliability result,
                int windowDays,
                Instant from,
                String timeZone,
                HistoryProperties.Reliability gate) {
            Coverage coverage = result.coverage();
            return new RouteReliabilityView(
                    routeId,
                    result.status(),
                    result.reason(),
                    windowDays,
                    from,
                    coverage.lastHour() == null ? null : coverage.lastHour().plus(1, ChronoUnit.HOURS),
                    timeZone,
                    Needs.of(gate),
                    CoverageView.of(coverage),
                    result.summary() == null ? null : FiguresView.of(result.summary()),
                    result.byHour().stream()
                            .map(cell -> new HourView(cell.hour(), cell.days(), FiguresView.of(cell.figures())))
                            .toList(),
                    result.byWeekday().stream()
                            .map(cell -> new WeekdayView(cell.isoWeekday(), cell.days(), FiguresView.of(cell.figures())))
                            .toList());
        }
    }

    /**
     * What a figure needs, copied from configuration so the client can say it instead of guessing.
     *
     * @param coveredHours   hours with enough samples that the route as a whole needs
     * @param samplesPerHour samples that make an hour count
     * @param judgedSamples  samples that reached a verdict that the route as a whole needs
     * @param cellSamples    judged samples behind one bar of a breakdown
     * @param daysPerHour    different days behind one hour of the day
     * @param daysPerWeekday different days behind one weekday
     */
    public record Needs(
            int coveredHours,
            int samplesPerHour,
            int judgedSamples,
            int cellSamples,
            int daysPerHour,
            int daysPerWeekday) {

        static Needs of(HistoryProperties.Reliability gate) {
            return new Needs(
                    gate.minCoveredHours(),
                    gate.minSamplesPerHour(),
                    gate.minJudgedSamples(),
                    gate.minCellSamples(),
                    gate.minDaysPerHourCell(),
                    gate.minDaysPerWeekdayCell());
        }
    }

    /**
     * @param hoursCovered  hours with enough samples to describe them
     * @param hoursExcluded hours with some samples but too few, left out of every figure
     * @param days          different Toronto days the covered hours fall on
     * @param firstHour     start of the earliest hour with any sample, or null
     * @param lastHour      start of the latest, or null
     */
    public record CoverageView(
            int hoursCovered,
            int hoursExcluded,
            int days,
            int samples,
            int judgedSamples,
            Instant firstHour,
            Instant lastHour) {

        static CoverageView of(Coverage coverage) {
            return new CoverageView(
                    coverage.hoursCovered(),
                    coverage.hoursExcluded(),
                    coverage.days(),
                    coverage.samples(),
                    coverage.judgedSamples(),
                    coverage.firstHour(),
                    coverage.lastHour());
        }
    }

    /**
     * @param samples       samples behind the figures; one is a route direction at one moment
     * @param judged        of those, how many reached a verdict
     * @param unjudged      how many did not, because too few vehicles were being tracked
     * @param steadyShare   judged samples that were excellent or normal, over judged samples
     * @param variation     mean headway variation, as a fraction
     * @param gapRatio      mean ratio of the largest gap to the expected spacing
     * @param bunchingShare judged samples with a bunch in them, over judged samples
     */
    public record FiguresView(
            int samples,
            int judged,
            int unjudged,
            int excellent,
            int normal,
            int unstable,
            int majorDisruption,
            Double steadyShare,
            Double variation,
            Double gapRatio,
            Double bunchingShare) {

        static FiguresView of(Figures figures) {
            return new FiguresView(
                    figures.samples(),
                    figures.judged(),
                    figures.unjudged(),
                    figures.excellent(),
                    figures.normal(),
                    figures.unstable(),
                    figures.majorDisruption(),
                    round(figures.steadyShare(), 1000),
                    round(figures.variation(), 100),
                    round(figures.gapRatio(), 100),
                    round(figures.bunchingShare(), 1000));
        }

        private static Double round(Double value, double scale) {
            return value == null ? null : Math.round(value * scale) / scale;
        }
    }

    /** @param hour 0 to 23 in the stated time zone */
    public record HourView(int hour, int days, FiguresView figures) {
    }

    /** @param isoWeekday 1 for Monday to 7 for Sunday */
    public record WeekdayView(int isoWeekday, int days, FiguresView figures) {
    }
}
