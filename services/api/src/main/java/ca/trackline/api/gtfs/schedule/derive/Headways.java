package ca.trackline.api.gtfs.schedule.derive;

import java.util.List;

/**
 * Headway statistics over scheduled departures.
 *
 * <p>Median rather than mean, because a route's gaps are not symmetric: one long
 * gap at the start of a service hour would drag a mean well above what riders
 * actually wait, and route health would then read normal service as sparse.
 */
public final class Headways {

    private Headways() {
    }

    /**
     * Median of the supplied gaps, in whole seconds.
     *
     * <p>An even count averages the two middle values and rounds to nearest, so the
     * result stays an integer without systematically biasing downwards.
     *
     * @param gapsSeconds gaps between consecutive departures; must not be empty
     */
    public static int medianSeconds(List<Integer> gapsSeconds) {
        if (gapsSeconds.isEmpty()) {
            throw new IllegalArgumentException("Cannot take a median of no gaps");
        }

        List<Integer> sorted = gapsSeconds.stream().sorted().toList();
        int size = sorted.size();
        int middle = size / 2;

        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        long sum = (long) sorted.get(middle - 1) + sorted.get(middle);
        return Math.toIntExact(Math.round(sum / 2.0));
    }

    /**
     * The clock hour a departure falls in.
     *
     * <p>GTFS expresses a 01:15 departure on a late-night trip as 25:15:00, so the
     * raw value has to be folded back into a 24-hour clock rather than truncated.
     */
    public static int clockHour(int departureSeconds) {
        return Math.floorMod(departureSeconds / 3600, 24);
    }
}
