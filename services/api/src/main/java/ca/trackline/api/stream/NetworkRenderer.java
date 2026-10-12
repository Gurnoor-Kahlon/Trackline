package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Renders the network stream: the same events as the vehicle stream, reduced to a
 * handful of counts.
 *
 * <p>Each message is the whole current status, so there is nothing to resume: a
 * client that missed one is corrected by the next. For that reason the messages
 * carry no event id, and a reconnecting client is simply sent the current status.
 */
final class NetworkRenderer implements StreamRenderer {

    private final Clock clock;

    NetworkRenderer(Clock clock) {
        this.clock = clock;
    }

    Outbound status(Instant feedTimestamp, int vehiclesTracked, int routesReporting) {
        return new Outbound(
                null,
                "network",
                WireFormat.network(Instant.now(clock), feedTimestamp, vehiclesTracked, routesReporting));
    }

    @Override
    public List<Outbound> render(StreamEvent event) {
        return List.of(status(event.feedTimestamp(), event.fleetSize(), event.routesReporting()));
    }

    @Override
    public Outbound heartbeat(Heartbeat heartbeat) {
        return new Outbound(
                null, "heartbeat", WireFormat.heartbeat(heartbeat.now(), heartbeat.feedTimestamp(), heartbeat.latestEventId()));
    }
}
