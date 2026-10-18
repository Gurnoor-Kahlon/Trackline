package ca.trackline.api.analytics.health;

import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.DirectionInputs;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.analytics.spacing.SpacingYardstick;

/**
 * Judges whether service on a route is coherent, from how evenly its vehicles are spaced.
 *
 * <p>Pure: figures in, verdict out. No Spring, no database, no clock, so every branch and
 * every threshold boundary is testable with arithmetic.
 *
 * <p>The measure is the regularity of spacing, because that is what the data supports. The
 * feed carries no delay for any trip, so there is no on-time figure to report, and no
 * "late by N minutes" is ever produced. What can be said is whether vehicles are bunched
 * together, strung out, or too few, and how that compares with what the timetable intends.
 *
 * <p>Rules, first match wins, with the thresholds in {@link HealthProperties}:
 * <ol>
 *   <li>fewer than two usable vehicles, or two with no timetable to compare against (one
 *       gap measured against its own average is always exactly average): UNKNOWN, no
 *       confidence;</li>
 *   <li>variation above the major band, or the largest gap above the major ratio, or fewer
 *       than the major share of the scheduled vehicles running: MAJOR_DISRUPTION;</li>
 *   <li>variation above the unstable band or the largest gap above the unstable ratio:
 *       UNSTABLE;</li>
 *   <li>variation above the excellent band or the largest gap above the excellent ratio:
 *       NORMAL;</li>
 *   <li>otherwise EXCELLENT.</li>
 * </ol>
 * A verdict resting on two vehicles is held to the middle of the range: never better than
 * NORMAL and never worse than UNSTABLE, since neither extreme is claimed on one gap.
 *
 * <p>A direction with a bunch, vehicles in a pack while a large gap opens elsewhere, is
 * also at least UNSTABLE and says so with the BUNCHING reason. One input the original design
 * named is not here, deliberately: service-alert severity. Every alert the TTC publishes
 * states an unknown effect, so a "no service" alert cannot be told from an elevator outage
 * by anything but its wording.
 */
public final class RouteHealthCalculator {

    private final HealthProperties thresholds;

    public RouteHealthCalculator(HealthProperties thresholds) {
        this.thresholds = thresholds;
    }

    /** Judges one direction of one route. */
    public DirectionHealth evaluate(DirectionInputs in) {
        List<ReasonCode> reasons = new ArrayList<>();

        if (in.analysedVehicles() < thresholds.minVehicles()) {
            reasons.add(ReasonCode.INSUFFICIENT_VEHICLES);
            return new DirectionHealth(
                    in.directionId(), RouteHealthState.UNKNOWN, HealthConfidence.NONE, reasons, emptyMetrics(in));
        }

        List<Double> gaps = in.gapsMetres();
        double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = gaps.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Double variation = variation(gaps, mean);

        SpacingYardstick yardstickSource =
                SpacingYardstick.of(in.scheduleCovered(), in.scheduledHeadwaySeconds(), in.speedMetresPerSecond());
        Double expectedGap = yardstickSource.scheduledMetres();
        SpacingBasis basis = yardstickSource.basis();
        if (yardstickSource.shortfall() != null) {
            reasons.add(yardstickSource.shortfall());
        }

        if (basis == SpacingBasis.OBSERVED && in.analysedVehicles() < thresholds.mediumConfidenceVehicles()) {
            // Two vehicles judged against their own average are always exactly average, so any
            // verdict would be an artefact of the method and not a finding.
            reasons.add(ReasonCode.FEW_VEHICLES);
            return new DirectionHealth(
                    in.directionId(), RouteHealthState.UNKNOWN, HealthConfidence.NONE, reasons,
                    new HealthMetrics(
                            in.analysedVehicles(), gaps.size(), mean, max, null, null, null, null, null,
                            in.scheduledHeadwaySeconds(), in.speedMetresPerSecond(), in.staleVehicles(),
                            in.terminalVehicles(), basis));
        }

        // Without a timetable the only yardstick is the vehicles' own average spacing, so the
        // largest gap is measured against that. It can still reveal one big hole among small
        // ones, but it cannot reveal a route whose every gap is too large.
        double yardstick = yardstickSource.metres(gaps);
        Double gapRatio = yardstick > 0 ? max / yardstick : null;

        Double expectedVehicles = null;
        Double supplyRatio = null;
        if (expectedGap != null && expectedGap > 0) {
            // At least one: a route whose spacing exceeds its length still needs a vehicle.
            expectedVehicles = Math.max(1.0, in.shapeLengthMetres() / expectedGap);
            supplyRatio = in.freshPlacedVehicles() / expectedVehicles;
        }

        RouteHealthState state = classify(variation, gapRatio, supplyRatio);

        // A pack with a gap elsewhere is uneven service by definition. With both measured against
        // the same yardstick the large gap already makes this at least unstable, so the floor is
        // normally redundant, but it is stated here so the rule holds on its own.
        if (in.bunchingClusters() > 0) {
            reasons.add(ReasonCode.BUNCHING);
            if (state == RouteHealthState.EXCELLENT || state == RouteHealthState.NORMAL) {
                state = RouteHealthState.UNSTABLE;
            }
        }

        if (variation != null && variation > thresholds.unstableCv()) {
            reasons.add(ReasonCode.HIGH_HEADWAY_VARIATION);
        }
        if (gapRatio != null && gapRatio > thresholds.unstableGapRatio()) {
            reasons.add(ReasonCode.LARGE_GAP);
        }
        if (supplyRatio != null && supplyRatio < thresholds.majorSupplyRatio()) {
            reasons.add(ReasonCode.FEWER_VEHICLES_THAN_SCHEDULED);
        }

        HealthConfidence confidence = confidence(in, basis, reasons);

        // A verdict resting on one gap does not get to be the best or the worst. One even gap
        // between two buses says almost nothing about the rest of the route, and one wide gap
        // between two buses may be a route that simply has two buses at this hour.
        if (confidence == HealthConfidence.LOW) {
            if (state == RouteHealthState.EXCELLENT) {
                state = RouteHealthState.NORMAL;
            } else if (state == RouteHealthState.MAJOR_DISRUPTION) {
                state = RouteHealthState.UNSTABLE;
            }
        }

        HealthMetrics metrics = new HealthMetrics(
                in.analysedVehicles(), gaps.size(), mean, max, variation, gapRatio, expectedGap,
                expectedVehicles, supplyRatio, in.scheduledHeadwaySeconds(), in.speedMetresPerSecond(),
                in.staleVehicles(), in.terminalVehicles(), basis, in.bunchingClusters());
        return new DirectionHealth(in.directionId(), state, confidence, reasons, metrics);
    }

    private RouteHealthState classify(Double variation, Double gapRatio, Double supplyRatio) {
        if (above(variation, thresholds.majorCv())
                || above(gapRatio, thresholds.majorGapRatio())
                || (supplyRatio != null && supplyRatio < thresholds.majorSupplyRatio())) {
            return RouteHealthState.MAJOR_DISRUPTION;
        }
        if (above(variation, thresholds.unstableCv()) || above(gapRatio, thresholds.unstableGapRatio())) {
            return RouteHealthState.UNSTABLE;
        }
        if (above(variation, thresholds.excellentCv()) || above(gapRatio, thresholds.excellentGapRatio())) {
            return RouteHealthState.NORMAL;
        }
        return RouteHealthState.EXCELLENT;
    }

    /** Strictly above: a figure exactly on a boundary belongs to the calmer band. */
    private static boolean above(Double value, double threshold) {
        return value != null && value > threshold;
    }

    private HealthConfidence confidence(DirectionInputs in, SpacingBasis basis, List<ReasonCode> reasons) {
        int vehicles = in.analysedVehicles();
        double stale = in.staleVehicles();
        double considered = stale + in.freshPlacedVehicles();
        boolean mostlyStale = considered > 0 && stale / considered > thresholds.staleFractionForLowConfidence();

        if (mostlyStale) {
            reasons.add(ReasonCode.STALE_VEHICLES);
        }
        if (vehicles < thresholds.mediumConfidenceVehicles()) {
            reasons.add(ReasonCode.FEW_VEHICLES);
        }

        if (mostlyStale || vehicles < thresholds.mediumConfidenceVehicles()) {
            return HealthConfidence.LOW;
        }
        if (vehicles >= thresholds.highConfidenceVehicles() && basis == SpacingBasis.SCHEDULE) {
            return HealthConfidence.HIGH;
        }
        return HealthConfidence.MEDIUM;
    }

    /**
     * Standard deviation of the gaps over their mean, or null when there are too few gaps
     * for it to mean anything: with two it is just their ratio, and cannot exceed one.
     */
    private Double variation(List<Double> gaps, double mean) {
        if (gaps.size() < thresholds.minGapsForVariation() || mean <= 0) {
            return null;
        }
        double squares = 0;
        for (double gap : gaps) {
            squares += (gap - mean) * (gap - mean);
        }
        return Math.sqrt(squares / gaps.size()) / mean;
    }

    private static HealthMetrics emptyMetrics(DirectionInputs in) {
        return new HealthMetrics(
                in.analysedVehicles(), in.gapsMetres().size(), null, null, null, null, null, null, null,
                in.scheduledHeadwaySeconds(), in.speedMetresPerSecond(), in.staleVehicles(),
                in.terminalVehicles(), SpacingBasis.OBSERVED);
    }

    /**
     * A route's verdict from its directions: the worse of the two, since a rider waiting
     * on the poorly served side is not helped by the other being fine.
     *
     * <p>Directions with no verdict are ignored while any other has one; a route is UNKNOWN
     * only when none does. The confidence and reasons are those of the decisive direction,
     * so the explanation describes the direction that set the state.
     */
    public RouteHealth combine(String routeId, List<DirectionHealth> directions, int unplacedVehicles) {
        DirectionHealth decisive = null;
        for (DirectionHealth candidate : directions) {
            if (decisive == null || moreDecisive(candidate, decisive)) {
                decisive = candidate;
            }
        }

        if (decisive == null) {
            return new RouteHealth(
                    routeId, RouteHealthState.UNKNOWN, HealthConfidence.NONE,
                    List.of(ReasonCode.INSUFFICIENT_VEHICLES), directions, unplacedVehicles);
        }
        return new RouteHealth(
                routeId, decisive.state(), decisive.confidence(), decisive.reasons(), directions, unplacedVehicles);
    }

    /** A real verdict beats none, a worse one beats a better one, and the weaker evidence breaks a tie. */
    private static boolean moreDecisive(DirectionHealth candidate, DirectionHealth current) {
        if (candidate.state().isKnown() != current.state().isKnown()) {
            return candidate.state().isKnown();
        }
        if (candidate.state().severity() != current.state().severity()) {
            return candidate.state().severity() > current.state().severity();
        }
        return candidate.confidence().isLowerThan(current.confidence());
    }
}
