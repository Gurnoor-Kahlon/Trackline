package ca.trackline.api.analytics.history;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How history is kept, summarised and trimmed, and how much of it a reliability figure needs before
 * it is shown.
 *
 * @param enabled           whether the hourly summary and the retention run on a schedule. Sampling is
 *                          part of the ingestion cycle and follows it
 * @param sampleInterval    how often route health is recorded. The analysis runs every cycle, about
 *                          every 20 seconds, and a verdict rarely changes between them
 * @param rollupInterval    how often finished hours are summarised
 * @param retentionInterval how often old history is removed
 * @param startupDelay      how long after start the first summary and trim wait, so they do not compete
 *                          with the first ingestion cycles
 * @param retention         how long each kind of history is kept
 * @param reliability       the amount of history a reliability figure needs
 */
@Validated
@ConfigurationProperties(prefix = "trackline.history")
public record HistoryProperties(
        boolean enabled,
        @NotNull Duration sampleInterval,
        @NotNull Duration rollupInterval,
        @NotNull Duration retentionInterval,
        @NotNull Duration startupDelay,
        @NotNull @Valid Retention retention,
        @NotNull @Valid Reliability reliability) {

    public HistoryProperties {
        requirePositive(sampleInterval, "sampleInterval");
        requirePositive(rollupInterval, "rollupInterval");
        requirePositive(retentionInterval, "retentionInterval");
        if (startupDelay == null || startupDelay.isNegative()) {
            throw new IllegalArgumentException("startupDelay must be set and cannot be negative");
        }
        if (retention == null || reliability == null) {
            throw new IllegalArgumentException("retention and reliability must be set");
        }
    }

    /**
     * @param samples       raw route health samples. The largest table, so the one that sets the storage cost
     * @param hourly        the hourly summary. It must outlive the samples it was built from, or
     *                      dropping a partition would lose history that was never summarised
     * @param observations  stored vehicle positions
     * @param bunchingEvents resolved bunching events; one still open is never removed
     * @param alerts        resolved service alerts, with the routes and stops they named; one still in force is never removed
     * @param refreshTokens how long an expired refresh token is kept after it expires. Sign-ins that are never used
     *                      again leave a row each, so without this the table only grows
     * @param ingestionRuns the per-cycle record of what ingestion did. Every stored position refers
     *                      to one, so these must outlive the positions
     */
    public record Retention(
            @NotNull Duration samples,
            @NotNull Duration hourly,
            @NotNull Duration observations,
            @NotNull Duration bunchingEvents,
            @NotNull Duration alerts,
            @NotNull Duration refreshTokens,
            @NotNull Duration ingestionRuns) {

        public Retention {
            requirePositive(samples, "retention.samples");
            requirePositive(hourly, "retention.hourly");
            requirePositive(observations, "retention.observations");
            requirePositive(bunchingEvents, "retention.bunchingEvents");
            requirePositive(alerts, "retention.alerts");
            requirePositive(refreshTokens, "retention.refreshTokens");
            requirePositive(ingestionRuns, "retention.ingestionRuns");
            if (hourly.compareTo(samples) < 0) {
                throw new IllegalArgumentException("retention.hourly cannot be shorter than retention.samples");
            }
            if (ingestionRuns.compareTo(observations) < 0) {
                throw new IllegalArgumentException(
                        "retention.ingestionRuns cannot be shorter than retention.observations: "
                                + "each stored position refers to the run that fetched it");
            }
        }
    }

    /**
     * What a reliability figure needs before it is shown. Below these it is withheld, because a
     * percentage over a few hours is an anecdote that looks like a statistic.
     *
     * @param minCoveredHours     fewest hours with enough samples for the route as a whole
     * @param minSamplesPerHour   samples an hour needs to count as covered. An hour the application
     *                            was running for three minutes says nothing about that hour
     * @param minJudgedSamples    fewest samples that reached a verdict. A route whose samples are all
     *                            "unknown" has been seen, but not judged
     * @param minCellSamples      fewest judged samples behind one bar of a breakdown
     * @param minDaysPerHourCell  fewest different days behind one hour of the day. One Tuesday evening
     *                            does not show what evenings are like
     * @param minDaysPerWeekdayCell fewest different days behind one weekday
     */
    public record Reliability(
            @Positive int minCoveredHours,
            @Positive int minSamplesPerHour,
            @Positive int minJudgedSamples,
            @Positive int minCellSamples,
            @Positive int minDaysPerHourCell,
            @Positive int minDaysPerWeekdayCell) {
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
