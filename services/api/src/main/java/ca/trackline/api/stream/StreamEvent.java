package ca.trackline.api.stream;

import java.time.Instant;
import java.util.List;

/**
 * One step in the live picture, shared by every client.
 *
 * <p>Built once per ingestion cycle, unfiltered, and kept in the replay ring.
 * Filtering happens per client at the moment of sending, so one event serves a
 * client watching a single route and one watching the whole fleet.
 *
 * <p>{@code previousId} makes the sequence verifiable. A client resuming from an id
 * is given the events after it only if they form an unbroken chain from that id;
 * a gap — the ring has wrapped, the server restarted, an id never issued — is
 * detected by the chain not connecting, and the client is sent a fresh snapshot
 * instead of a silent hole.
 *
 * @param id               unique, increasing, and unique across restarts
 * @param previousId       the id of the event before this one, or 0 for the first
 * @param kind             a full dataset or a change
 * @param feedTimestamp    the publisher's timestamp for the data this describes
 * @param fleetSize        vehicles tracked after this event
 * @param routesReporting  distinct routes among them
 * @param snapshot         the whole fleet, for {@link Kind#SNAPSHOT}; empty otherwise
 * @param changed          vehicles to add or update, for {@link Kind#DELTA}
 * @param removed          vehicles that stopped reporting, for {@link Kind#DELTA}
 */
public record StreamEvent(
        long id,
        long previousId,
        Kind kind,
        Instant feedTimestamp,
        int fleetSize,
        int routesReporting,
        List<Tracked> snapshot,
        List<Change> changed,
        List<Gone> removed) {

    public enum Kind {
        /** Replaces everything the client holds. */
        SNAPSHOT,
        /** Applies to what the client holds. */
        DELTA
    }

    /**
     * @param now    the vehicle as it is now
     * @param before where it was when the client last heard of it, or null if new
     */
    public record Change(Tracked now, Where before) {
    }

    /**
     * @param id       the vehicle that stopped reporting
     * @param lastSeen where it was, so a filter can tell whether the client has it
     */
    public record Gone(String id, Where lastSeen) {
    }
}
