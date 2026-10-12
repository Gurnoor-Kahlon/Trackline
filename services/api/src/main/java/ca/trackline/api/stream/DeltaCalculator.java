package ca.trackline.api.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ca.trackline.api.stream.StreamEvent.Change;
import ca.trackline.api.stream.StreamEvent.Gone;

/**
 * Decides which vehicles are worth sending after a feed cycle.
 *
 * <p>Sending all 1,500 vehicles every cycle would work, and would be mostly noise:
 * a bus held at a light reports again at the same spot. A vehicle is re-sent when
 * the picture a client holds of it would actually change:
 *
 * <ul>
 *   <li>it is new, or has changed route or occupancy, which are drawn;
 *   <li>it has moved more than a few metres from <em>where it was last sent</em>;
 *   <li>or it has gone a full keepalive interval without being sent, so a vehicle
 *       standing still does not look silent and get dimmed as stale.
 * </ul>
 *
 * <p>Movement is measured from the last position sent, not from the previous cycle.
 * Measured cycle to cycle, a vehicle creeping one metre at a time would never cross
 * the threshold and would drift arbitrarily far from where clients have it.
 *
 * <p>A reading whose timestamp has not advanced is the same reading delivered again
 * and never counts as a change.
 */
public final class DeltaCalculator {

    private final double moveThresholdMetres;
    private final long keepaliveSeconds;

    public DeltaCalculator(double moveThresholdMetres, long keepaliveSeconds) {
        this.moveThresholdMetres = moveThresholdMetres;
        this.keepaliveSeconds = keepaliveSeconds;
    }

    /**
     * @param lastSent      each vehicle as it was last sent
     * @param previousCycle each vehicle as the previous cycle reported it
     * @param current       each vehicle as this cycle reports it
     */
    public Delta compute(
            Map<String, Tracked> lastSent, Map<String, Tracked> previousCycle, Map<String, Tracked> current) {
        List<Change> changed = new ArrayList<>();
        Map<String, Tracked> nextSent = new HashMap<>(lastSent.size());

        for (Tracked now : current.values()) {
            Tracked sent = lastSent.get(now.id());
            if (sent == null) {
                changed.add(new Change(now, null));
                nextSent.put(now.id(), now);
            } else if (worthSending(sent, now)) {
                Tracked before = previousCycle.getOrDefault(now.id(), sent);
                changed.add(new Change(now, before.where()));
                nextSent.put(now.id(), now);
            } else {
                nextSent.put(now.id(), sent);
            }
        }

        List<Gone> removed = new ArrayList<>();
        for (Tracked sent : lastSent.values()) {
            if (!current.containsKey(sent.id())) {
                Tracked seen = previousCycle.getOrDefault(sent.id(), sent);
                removed.add(new Gone(sent.id(), seen.where()));
            }
        }

        return new Delta(changed, removed, nextSent);
    }

    private boolean worthSending(Tracked sent, Tracked now) {
        if (now.timestamp() <= sent.timestamp()) {
            return false;
        }
        return !Objects.equals(sent.route(), now.route())
                || !Objects.equals(sent.occupancy(), now.occupancy())
                || metresBetween(sent.lat(), sent.lon(), now.lat(), now.lon()) > moveThresholdMetres
                || now.timestamp() - sent.timestamp() >= keepaliveSeconds;
    }

    /**
     * Equirectangular approximation: well under a metre of error at the few metres
     * this is used to compare, and no database round trip.
     */
    static double metresBetween(double fromLat, double fromLon, double toLat, double toLon) {
        double metresPerDegree = 111_320;
        double northing = (toLat - fromLat) * metresPerDegree;
        double easting = (toLon - fromLon) * metresPerDegree * Math.cos(Math.toRadians((fromLat + toLat) / 2));
        return Math.hypot(northing, easting);
    }

    /**
     * @param changed  vehicles to add or update
     * @param removed  vehicles that stopped reporting
     * @param nextSent what clients now hold, to compare the next cycle against
     */
    public record Delta(List<Change> changed, List<Gone> removed, Map<String, Tracked> nextSent) {

        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }
    }
}
