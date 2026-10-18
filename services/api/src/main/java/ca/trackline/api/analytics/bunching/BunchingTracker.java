package ca.trackline.api.analytics.bunching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;

/**
 * Decides which bunches an analysis is a new sighting of and which it is a fresh sighting of.
 *
 * <p>Pure, like the reconciler for alerts: it is given what is open and what was found and
 * says what to do about each, so every case of the lifecycle is testable without a database.
 *
 * <p>A bunch persists across cycles for as long as vehicles stay together, and writing a row
 * every cycle would turn one bunch into hundreds. So it is opened once, updated while it
 * persists, and resolved when it has been absent for several consecutive analyses. The
 * identity of a bunch is its route, its direction and its vehicles, but not exactly its
 * vehicles: a bunch that picks up a third bus is still that bunch, so a new sighting continues
 * an open one when they share enough vehicles.
 */
public final class BunchingTracker {

    private final BunchingProperties properties;

    public BunchingTracker(BunchingProperties properties) {
        this.properties = properties;
    }

    /** A bunch that is open in the store. */
    public record Open(long id, String routeId, short directionId, Set<String> vehicleIds, int missedCycles) {
    }

    /** A bunch found now that continues an open one. */
    public record Continued(long id, BunchingCandidate candidate) {
    }

    /** A bunch that was not seen this time, and its running count of consecutive misses. */
    public record Missed(long id, int missedCycles) {
    }

    /**
     * @param toOpen     bunches with no open predecessor
     * @param continued  open bunches seen again
     * @param missed     open bunches not seen, still within the grace period
     * @param toResolve  open bunches not seen for long enough to be called over
     */
    public record Plan(List<BunchingCandidate> toOpen, List<Continued> continued, List<Missed> missed, List<Long> toResolve) {
    }

    public Plan reconcile(Collection<Open> open, List<BunchingCandidate> found) {
        Map<String, List<Open>> openByDirection = new LinkedHashMap<>();
        for (Open event : open) {
            openByDirection.computeIfAbsent(key(event.routeId(), event.directionId()), k -> new ArrayList<>()).add(event);
        }
        Map<String, List<BunchingCandidate>> foundByDirection = new LinkedHashMap<>();
        for (BunchingCandidate candidate : found) {
            foundByDirection
                    .computeIfAbsent(key(candidate.routeId(), candidate.directionId()), k -> new ArrayList<>())
                    .add(candidate);
        }

        List<BunchingCandidate> toOpen = new ArrayList<>();
        List<Continued> continued = new ArrayList<>();
        List<Missed> missed = new ArrayList<>();
        List<Long> toResolve = new ArrayList<>();

        Set<String> directions = new LinkedHashSet<>(openByDirection.keySet());
        directions.addAll(foundByDirection.keySet());

        for (String direction : directions) {
            matchWithin(
                    openByDirection.getOrDefault(direction, List.of()),
                    foundByDirection.getOrDefault(direction, List.of()),
                    toOpen, continued, missed, toResolve);
        }
        return new Plan(List.copyOf(toOpen), List.copyOf(continued), List.copyOf(missed), List.copyOf(toResolve));
    }

    /**
     * Pairs open bunches with new ones within one direction, largest overlap first.
     *
     * <p>Greedy by overlap is enough: bunches on one direction are few, rarely share vehicles, and
     * when one open bunch overlaps two new ones (a split) the larger overlap continues it and the
     * other is new, while two open bunches overlapping one new one (a merge) leaves the weaker to
     * be missed and resolved in due course.
     */
    private void matchWithin(
            List<Open> open,
            List<BunchingCandidate> found,
            List<BunchingCandidate> toOpen,
            List<Continued> continued,
            List<Missed> missed,
            List<Long> toResolve) {

        record Pair(int open, int found, int shared) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int o = 0; o < open.size(); o++) {
            for (int f = 0; f < found.size(); f++) {
                Set<String> overlap = new HashSet<>(open.get(o).vehicleIds());
                overlap.retainAll(found.get(f).vehicleIds());
                if (overlap.size() >= properties.minSharedVehicles()) {
                    pairs.add(new Pair(o, f, overlap.size()));
                }
            }
        }
        // Most shared first; ties go to the earlier-opened event and then the earlier candidate.
        pairs.sort((a, b) -> a.shared() != b.shared()
                ? Integer.compare(b.shared(), a.shared())
                : a.open() != b.open() ? Integer.compare(a.open(), b.open()) : Integer.compare(a.found(), b.found()));

        boolean[] openTaken = new boolean[open.size()];
        boolean[] foundTaken = new boolean[found.size()];
        for (Pair pair : pairs) {
            if (!openTaken[pair.open()] && !foundTaken[pair.found()]) {
                openTaken[pair.open()] = true;
                foundTaken[pair.found()] = true;
                continued.add(new Continued(open.get(pair.open()).id(), found.get(pair.found())));
            }
        }

        for (int f = 0; f < found.size(); f++) {
            if (!foundTaken[f]) {
                toOpen.add(found.get(f));
            }
        }
        for (int o = 0; o < open.size(); o++) {
            if (!openTaken[o]) {
                int misses = open.get(o).missedCycles() + 1;
                if (misses >= properties.cyclesToResolve()) {
                    toResolve.add(open.get(o).id());
                } else {
                    missed.add(new Missed(open.get(o).id(), misses));
                }
            }
        }
    }

    private static String key(String routeId, short directionId) {
        return routeId + "/" + directionId;
    }
}
