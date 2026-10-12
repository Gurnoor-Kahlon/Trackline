package ca.trackline.api.stream;

import java.time.Instant;
import java.util.List;

/**
 * Turns the shared events into what one particular client should be sent.
 *
 * <p>The seam between "what happened", which is the same for everyone, and "what
 * this client asked to see", which is not. A vehicle renderer applies the client's
 * filter; a network renderer reduces the same events to a few counts.
 */
interface StreamRenderer {

    /** What to send for an event; empty when the event changes nothing this client can see. */
    List<Outbound> render(StreamEvent event);

    /** What to send when the connection has been quiet. */
    Outbound heartbeat(Heartbeat heartbeat);

    /**
     * @param feedTimestamp the newest feed timestamp known, or null before the first cycle
     * @param latestEventId the newest event id issued, or 0
     * @param now           when the heartbeat is being sent
     */
    record Heartbeat(Instant feedTimestamp, long latestEventId, Instant now) {
    }
}
