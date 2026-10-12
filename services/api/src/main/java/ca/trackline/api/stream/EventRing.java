package ca.trackline.api.stream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The most recent events, kept so a client that dropped out can pick up where it
 * left off instead of starting over.
 *
 * <p>In process, deliberately. A reconnecting client reaches the same instance that
 * is ingesting (the deployment runs one, because two would poll the feed twice), so
 * a shared store would add a write per cycle and a failure mode while resuming no
 * one who could not already resume. It also keeps streaming independent of Redis:
 * the live cache can be down and clients still get every delta.
 *
 * <p>Resuming is only offered when it can be proved correct. See
 * {@link #replayAfter(long)}.
 */
public final class EventRing {

    private final int capacity;
    private final Deque<StreamEvent> events = new ArrayDeque<>();

    public EventRing(int capacity) {
        this.capacity = capacity;
    }

    /** The newest id, or 0 when nothing has been published. */
    public synchronized long latestId() {
        return events.isEmpty() ? 0 : events.getLast().id();
    }

    /**
     * The id for the next event.
     *
     * <p>Time-based, and never lower than the previous id plus one. A counter
     * restarting at 1 after a restart could reissue an id a client still holds, and
     * the chain check would then accept it and resume a client onto a stream it
     * never saw. Milliseconds since the epoch do not collide across restarts.
     */
    public synchronized long nextId(long nowMillis) {
        return Math.max(latestId() + 1, nowMillis);
    }

    public synchronized void append(StreamEvent event) {
        events.addLast(event);
        while (events.size() > capacity) {
            events.removeFirst();
        }
    }

    /**
     * The events after {@code lastId}, if and only if they connect to it.
     *
     * <p>Empty means "cannot resume": the id is too old and has fallen out of the
     * ring, or was never issued by this process, or the events do not form an
     * unbroken chain from it. The caller then sends a full snapshot. Each case ends
     * the same way, which is the point: a hole in the stream must never be mistaken
     * for "nothing happened".
     *
     * <p>A client already at the newest id gets an empty list, meaning "you are
     * current", which is different from an empty optional.
     */
    public synchronized Optional<List<StreamEvent>> replayAfter(long lastId) {
        if (events.isEmpty()) {
            return Optional.empty();
        }
        if (lastId == events.getLast().id()) {
            return Optional.of(List.of());
        }

        List<StreamEvent> after = new ArrayList<>();
        long expectedPrevious = lastId;
        boolean connected = false;
        for (StreamEvent event : events) {
            if (!connected) {
                if (event.previousId() != lastId) {
                    continue;
                }
                connected = true;
            }
            if (event.previousId() != expectedPrevious) {
                return Optional.empty();
            }
            after.add(event);
            expectedPrevious = event.id();
        }
        return connected ? Optional.of(after) : Optional.empty();
    }

    /** Forgets every event. For tests that need a ring as it is at startup. */
    synchronized void clear() {
        events.clear();
    }

    public synchronized int size() {
        return events.size();
    }
}
