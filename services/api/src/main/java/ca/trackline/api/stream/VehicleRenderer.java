package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import ca.trackline.api.stream.StreamEvent.Change;
import ca.trackline.api.stream.StreamEvent.Gone;

/**
 * Renders the vehicle stream for one client, applying that client's filter.
 *
 * <p>A filter has two edges and both are handled. A vehicle that matches is sent.
 * A vehicle that has stopped matching, because it left the area or changed route, is
 * sent as a removal if the client had it, which is known from where it was before.
 * Without that second half a filtered map would keep ghosts of vehicles that drove
 * away.
 */
final class VehicleRenderer implements StreamRenderer {

    private final StreamFilter filter;
    private final Clock clock;

    VehicleRenderer(StreamFilter filter, Clock clock) {
        this.filter = filter;
        this.clock = clock;
    }

    /** The whole matching fleet, labelled with the event id that later changes chain from. */
    Outbound snapshot(long eventId, Instant feedTimestamp, Collection<Tracked> fleet) {
        List<Tracked> matching = fleet.stream().filter(filter::matches).toList();
        return new Outbound(
                Long.toString(eventId), "snapshot", WireFormat.snapshot(Instant.now(clock), feedTimestamp, matching));
    }

    @Override
    public List<Outbound> render(StreamEvent event) {
        Instant now = Instant.now(clock);

        if (event.kind() == StreamEvent.Kind.SNAPSHOT) {
            List<Tracked> matching = event.snapshot().stream().filter(filter::matches).toList();
            return List.of(new Outbound(
                    Long.toString(event.id()), "snapshot", WireFormat.snapshot(now, event.feedTimestamp(), matching)));
        }

        List<Tracked> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (Change change : event.changed()) {
            if (filter.matches(change.now())) {
                changed.add(change.now());
            } else if (change.before() != null && filter.matches(change.before())) {
                // It was inside the client's view and no longer is.
                removed.add(change.now().id());
            }
        }
        for (Gone gone : event.removed()) {
            if (filter.matches(gone.lastSeen())) {
                removed.add(gone.id());
            }
        }

        if (changed.isEmpty() && removed.isEmpty()) {
            return List.of();
        }
        return List.of(new Outbound(
                Long.toString(event.id()),
                "vehicles",
                WireFormat.delta(now, event.feedTimestamp(), changed, removed)));
    }

    @Override
    public Outbound heartbeat(Heartbeat heartbeat) {
        return new Outbound(
                null, "heartbeat", WireFormat.heartbeat(heartbeat.now(), heartbeat.feedTimestamp(), heartbeat.latestEventId()));
    }
}
