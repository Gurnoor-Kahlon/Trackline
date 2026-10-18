package ca.trackline.api.analytics.spacing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Distances between consecutive vehicles along a route.
 *
 * <p>Measured along the route's own shape, never in a straight line: two vehicles on
 * opposite legs of a winding route are close as the crow flies and far apart as a rider
 * experiences them.
 */
public final class HeadwaySeries {

    private HeadwaySeries() {
    }

    /**
     * @param progressMetres where each vehicle is along the shape, in any order
     * @param lengthMetres   the shape's length, needed only to close a loop
     * @param loop           whether the route returns to its start, so the gap from the last
     *                       vehicle round to the first is a gap like any other
     * @return the gaps in order along the route: one fewer than the vehicles for a line, as
     *         many as there are vehicles for a loop; empty with fewer than two vehicles
     */
    public static List<Double> gaps(Collection<Double> progressMetres, double lengthMetres, boolean loop) {
        List<Double> ordered = new ArrayList<>(progressMetres);
        if (ordered.size() < 2) {
            return List.of();
        }
        ordered.sort(Double::compare);

        List<Double> gaps = new ArrayList<>(ordered.size());
        for (int index = 1; index < ordered.size(); index++) {
            gaps.add(ordered.get(index) - ordered.get(index - 1));
        }
        if (loop) {
            // Past the end of a loop is the start again.
            gaps.add(lengthMetres - (ordered.getLast() - ordered.getFirst()));
        }
        return gaps;
    }
}
