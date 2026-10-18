package ca.trackline.api.analytics.bunching;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;

/**
 * Holds a newly found pack back until it has persisted, so a pack that is only passing is not an event.
 *
 * <p>The first live run recorded packs that lasted one or two analyses, about twenty seconds each: vehicles
 * overtaking at a stop, a position that jittered across a projection boundary. Writing each as an event
 * made the history mostly noise. A pack must be seen on several consecutive analyses before it is recorded,
 * and is then recorded from its first sighting, so the time it was first seen is not lost to the wait.
 *
 * <p>Held in memory and rebuilt from live analyses: a restart forgets what was pending and a pack has to
 * be seen again, which costs a minute of events and nothing else. A pack that continues an event already
 * open skips the wait, since that event is exactly what a brief absence must not end.
 *
 * <p>Pure apart from its own state: given the packs found and when, it says which are confirmed.
 * Synchronised, since analyses of different cycles may overlap.
 */
public final class BunchingConfirmer {

    private final BunchingProperties properties;
    private final List<Pending> pending = new ArrayList<>();

    public BunchingConfirmer(BunchingProperties properties) {
        this.properties = properties;
    }

    private static final class Pending {
        BunchingCandidate latest;
        final Instant firstSeenAt;
        int sightings;

        Pending(BunchingCandidate latest, Instant firstSeenAt) {
            this.latest = latest;
            this.firstSeenAt = firstSeenAt;
            this.sightings = 1;
        }
    }

    /**
     * A pack fit to be recorded.
     *
     * @param firstSeenAt the first analysis of the run this pack has been seen on, which is what
     *                    the event is dated from
     * @param sightings   how many consecutive analyses it has been seen on
     */
    public record Confirmed(BunchingCandidate candidate, Instant firstSeenAt, int sightings) {
    }

    /**
     * @param found                what this analysis found
     * @param continuesOpenEvent   whether a pack continues an event that is already recorded
     * @param now                  when the analysis ran
     * @return the packs to record or continue; those not yet seen long enough are held back
     */
    public synchronized List<Confirmed> update(
            List<BunchingCandidate> found, Predicate<BunchingCandidate> continuesOpenEvent, Instant now) {

        List<Pending> next = new ArrayList<>();
        List<Confirmed> confirmed = new ArrayList<>();
        Set<Pending> taken = new HashSet<>();

        for (BunchingCandidate candidate : found) {
            Pending previous = bestMatch(candidate, taken);
            Pending current;
            if (previous == null) {
                current = new Pending(candidate, now);
            } else {
                taken.add(previous);
                previous.latest = candidate;
                previous.sightings++;
                current = previous;
            }
            // Only consecutive analyses count, so anything not seen this time is dropped below.
            next.add(current);

            if (continuesOpenEvent.test(candidate) || current.sightings >= properties.cyclesToConfirm()) {
                confirmed.add(new Confirmed(candidate, current.firstSeenAt, current.sightings));
            }
        }

        pending.clear();
        pending.addAll(next);
        return confirmed;
    }

    /** The pending pack on the same direction sharing the most vehicles, if enough, not already claimed. */
    private Pending bestMatch(BunchingCandidate candidate, Set<Pending> taken) {
        Pending best = null;
        int bestShared = 0;
        for (Pending held : pending) {
            if (taken.contains(held)
                    || !held.latest.routeId().equals(candidate.routeId())
                    || held.latest.directionId() != candidate.directionId()) {
                continue;
            }
            Set<String> shared = new HashSet<>(held.latest.vehicleIds());
            shared.retainAll(candidate.vehicleIds());
            if (shared.size() >= properties.minSharedVehicles() && shared.size() > bestShared) {
                best = held;
                bestShared = shared.size();
            }
        }
        return best;
    }

    /** Forgets everything pending. For tests. */
    public synchronized void forget() {
        pending.clear();
    }
}
