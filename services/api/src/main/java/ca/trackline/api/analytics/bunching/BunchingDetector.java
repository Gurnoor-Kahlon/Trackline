package ca.trackline.api.analytics.bunching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingModels.SpacedVehicle;
import ca.trackline.api.analytics.spacing.SpacingBasis;

/**
 * Finds vehicles running in a pack while a gap opens elsewhere on the same route.
 *
 * <p>Pure: positions in, candidates out, no clock and no database. A pack alone is not
 * bunching. A dense, evenly spaced route is a pack everywhere and nobody waits longer for it,
 * so a bunch is reported only if a gap above the service-gap ratio exists on the direction as
 * well. Both halves are required, and the negative case, vehicles close together with no
 * gap anywhere, is the one that matters most to get right.
 *
 * <p>What it is given has already had the vehicles that must not take part removed: those
 * with no direction, those that have stopped reporting, and those parked at a terminal, where
 * layovers legitimately sit side by side. So a layover is never mistaken for a bunch.
 *
 * <p>On a loop the pack and the gap can straddle the point where the shape starts and ends,
 * so the vehicles are treated as a ring.
 */
public final class BunchingDetector {

    private final BunchingProperties properties;

    public BunchingDetector(BunchingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param vehicles          the usable vehicles on one direction, in any order
     * @param lengthMetres      the direction's shape length, used to close a loop
     * @param loop              whether the route returns to its start
     * @param expectedGapMetres what spacing is measured against; see {@code SpacingYardstick}
     * @param basis             where that came from, recorded with each result
     * @return the bunches, in order along the route; empty when there is no bunch, when there is
     *         no gap to go with it, or when there are too few vehicles to say
     */
    public List<BunchingCandidate> detect(
            String routeId,
            short directionId,
            List<SpacedVehicle> vehicles,
            double lengthMetres,
            boolean loop,
            double expectedGapMetres,
            SpacingBasis basis) {

        int count = vehicles.size();
        int needed = basis == SpacingBasis.SCHEDULE ? properties.minVehicles() : properties.minVehiclesWithoutTimetable();
        if (count < needed || !(expectedGapMetres > 0)) {
            return List.of();
        }

        List<SpacedVehicle> ordered = new ArrayList<>(vehicles);
        ordered.sort(Comparator.comparingDouble(SpacedVehicle::progressMetres).thenComparing(SpacedVehicle::vehicleId));

        int gapCount = loop ? count : count - 1;
        double[] gaps = new double[gapCount];
        for (int index = 0; index < count - 1; index++) {
            gaps[index] = ordered.get(index + 1).progressMetres() - ordered.get(index).progressMetres();
        }
        if (loop) {
            gaps[count - 1] = lengthMetres - (ordered.getLast().progressMetres() - ordered.getFirst().progressMetres());
        }

        double closeBelow = properties.closeRatio() * expectedGapMetres;
        double serviceGap = 0;
        int firstOpenGap = -1;
        for (int index = 0; index < gapCount; index++) {
            if (gaps[index] >= closeBelow) {
                serviceGap = Math.max(serviceGap, gaps[index]);
                if (firstOpenGap < 0) {
                    firstOpenGap = index;
                }
            }
        }
        // Every neighbour close to the next is a route that is dense everywhere, with no gap for
        // anyone to wait in. Not bunching.
        if (firstOpenGap < 0 || serviceGap <= properties.serviceGapRatio() * expectedGapMetres) {
            return List.of();
        }

        // A loop is walked from just after a gap that is not close, so that no pack is split by the
        // arbitrary place the shape begins. A line is walked from its start.
        int start = loop ? (firstOpenGap + 1) % count : 0;
        BunchingSeverity gapSeverity = serviceGap > properties.severeGapRatio() * expectedGapMetres
                ? BunchingSeverity.SEVERE
                : BunchingSeverity.MODERATE;

        List<BunchingCandidate> found = new ArrayList<>();
        int position = 0;
        while (position < count) {
            int last = position;
            double span = 0;
            while (last < count - 1) {
                double gap = gaps[(start + last) % gapCount];
                if (gap >= closeBelow) {
                    break;
                }
                span += gap;
                last++;
            }
            if (last > position) {
                List<String> ids = new ArrayList<>();
                for (int member = position; member <= last; member++) {
                    ids.add(ordered.get((start + member) % count).vehicleId());
                }
                ids.sort(String::compareTo);

                BunchingSeverity severity = ids.size() >= properties.severeClusterSize()
                        ? BunchingSeverity.SEVERE
                        : gapSeverity;
                found.add(new BunchingCandidate(
                        routeId, directionId, ids, span, expectedGapMetres, serviceGap, severity, basis));
            }
            position = last + 1;
        }
        return found;
    }
}
