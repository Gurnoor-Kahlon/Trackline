package ca.trackline.api.analytics.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How fast vehicles are making progress along a route, measured from where they have been.
 *
 * <p>The timetable states spacing in time, and the vehicles' positions are in distance. The
 * conversion between them is speed, and the speed that matters is not what a bus reports
 * (the feed's speed field is frequently zero and low-trust) but how far along the route it
 * actually gets, stops and signals included. That is measured here from the projected
 * progress of each vehicle over the last few minutes.
 *
 * <p>Held in memory and rebuilt from live readings: after a restart a route reports no
 * speed for the first few minutes, and its verdict says so rather than guessing one.
 *
 * <p>Each vehicle contributes one figure, its net progress over the readings it has in
 * this route and direction, and a route's speed is the median across vehicles. A median so
 * that the vehicles sitting out a layover at a terminal, which make no progress, do not
 * drag the figure down while they are the minority, and a route whose median is below walking
 * pace reports none at all, because its vehicles are parked. A vehicle that turns round starts a
 * fresh run, since progress in the other direction is a different stretch of shape.
 *
 * <p>Thread-safe: methods are synchronised, since cycles of different feeds may overlap.
 */
public final class RouteSpeedEstimator {

    private final HealthProperties thresholds;
    private final Map<String, Run> runs = new HashMap<>();

    public RouteSpeedEstimator(HealthProperties thresholds) {
        this.thresholds = thresholds;
    }

    private record Reading(Instant at, double progressMetres) {
    }

    private static final class Run {
        final String routeId;
        final short directionId;
        final Deque<Reading> readings = new ArrayDeque<>();

        Run(String routeId, short directionId) {
            this.routeId = routeId;
            this.directionId = directionId;
        }
    }

    /**
     * Records where a vehicle was.
     *
     * <p>A reading no newer than the last one is ignored: the feed repeats a vehicle that
     * has not reported again, and counting it twice would dilute the span without adding
     * any progress.
     */
    public synchronized void observe(
            String vehicleId, String routeId, short directionId, double progressMetres, Instant at) {
        Run run = runs.get(vehicleId);
        if (run == null || !run.routeId.equals(routeId) || run.directionId != directionId) {
            run = new Run(routeId, directionId);
            runs.put(vehicleId, run);
        }
        Reading last = run.readings.peekLast();
        if (last != null && !at.isAfter(last.at())) {
            return;
        }
        run.readings.addLast(new Reading(at, progressMetres));
    }

    /** Drops what has aged out of the window, and vehicles that have stopped reporting. */
    public synchronized void prune(Instant now) {
        Instant oldest = now.minus(thresholds.speedWindow());
        runs.values().removeIf(run -> {
            while (!run.readings.isEmpty() && run.readings.peekFirst().at().isBefore(oldest)) {
                run.readings.removeFirst();
            }
            return run.readings.isEmpty();
        });
    }

    /**
     * @return the median progress speed of the vehicles on this route and direction, or
     *         empty when too few vehicles have been watched for long enough to say
     */
    public synchronized Optional<Double> speedMetresPerSecond(String routeId, short directionId) {
        List<Double> speeds = new ArrayList<>();
        for (Run run : runs.values()) {
            if (!run.routeId.equals(routeId) || run.directionId != directionId) {
                continue;
            }
            vehicleSpeed(run).ifPresent(speeds::add);
        }
        if (speeds.size() < thresholds.minSpeedVehicles()) {
            return Optional.empty();
        }
        speeds.sort(Double::compare);
        int middle = speeds.size() / 2;
        double median = speeds.size() % 2 == 1 ? speeds.get(middle) : (speeds.get(middle - 1) + speeds.get(middle)) / 2;
        // Most of the vehicles are parked: this is not how fast the route runs, and using it
        // would shrink the expected spacing to metres.
        return median < thresholds.minPlausibleSpeedMetresPerSecond() ? Optional.empty() : Optional.of(median);
    }

    private Optional<Double> vehicleSpeed(Run run) {
        if (run.readings.size() < thresholds.minSpeedReadings()) {
            return Optional.empty();
        }
        Reading first = run.readings.peekFirst();
        Reading last = run.readings.peekLast();
        Duration span = Duration.between(first.at(), last.at());
        if (span.compareTo(thresholds.minSpeedSpan()) < 0) {
            return Optional.empty();
        }
        double net = last.progressMetres() - first.progressMetres();
        // Backwards is a projection flip or a vehicle that doubled back, not a speed.
        if (net < 0) {
            return Optional.empty();
        }
        double speed = net / span.toMillis() * 1000.0;
        if (speed > thresholds.maxPlausibleSpeedMetresPerSecond()) {
            return Optional.empty();
        }
        return Optional.of(speed);
    }

    /** Forgets everything. For tests, which need an estimator with no history. */
    public synchronized void forget() {
        runs.clear();
    }
}
