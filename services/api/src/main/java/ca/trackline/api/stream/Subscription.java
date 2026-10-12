package ca.trackline.api.stream;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import ca.trackline.api.stream.StreamRenderer.Heartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One connected client.
 *
 * <p>Events are not written by whoever produces them. They are queued here and
 * written by a delivery task on a virtual thread, one task at a time per client, so
 * that the ingestion thread never waits on a client's network, one client's write
 * never waits on another's, and a client's events are always written in order.
 *
 * <p>The queue is bounded. A client that cannot keep up is dropped rather than
 * allowed to grow an unbounded backlog or to hold back the rest: it reconnects, and
 * resumes from the replay ring if it has not fallen too far behind. Dropping is
 * handed to another thread because closing an emitter whose write is blocked on a
 * stalled socket would otherwise block the thread that noticed.
 */
final class Subscription {

    private static final Logger log = LoggerFactory.getLogger(Subscription.class);

    /** Asked of the client on its first event, so a drop is retried promptly. */
    private static final long RETRY_MILLIS = 3_000;

    private final String channel;
    private final String clientKey;
    private final SseEmitter emitter;
    private final StreamRenderer renderer;
    private final Executor executor;
    private final int capacity;
    private final StreamMetrics metrics;
    private final Consumer<Subscription> onClosed;

    private final Queue<Object> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger depth = new AtomicInteger();
    private final AtomicBoolean delivering = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean firstEventSent;

    Subscription(
            String channel,
            String clientKey,
            SseEmitter emitter,
            StreamRenderer renderer,
            Executor executor,
            int capacity,
            StreamMetrics metrics,
            Consumer<Subscription> onClosed) {
        this.channel = channel;
        this.clientKey = clientKey;
        this.emitter = emitter;
        this.renderer = renderer;
        this.executor = executor;
        this.capacity = capacity;
        this.metrics = metrics;
        this.onClosed = onClosed;

        emitter.onCompletion(this::closeQuietly);
        emitter.onTimeout(() -> {
            // Ending the response is what asks the client to reconnect, which is how
            // a long-lived connection is renewed without the client doing anything.
            closeQuietly();
            emitter.complete();
        });
        emitter.onError(failure -> closeQuietly());
    }

    String channel() {
        return channel;
    }

    String clientKey() {
        return clientKey;
    }

    boolean isClosed() {
        return closed.get();
    }

    /** Queues something already rendered, such as the opening snapshot. */
    void offer(Outbound outbound) {
        enqueue(outbound);
    }

    /** Queues a shared event, to be rendered through this client's filter when it is sent. */
    void offer(StreamEvent event) {
        enqueue(event);
    }

    void offer(Heartbeat heartbeat) {
        enqueue(heartbeat);
    }

    private void enqueue(Object item) {
        if (closed.get()) {
            return;
        }
        if (depth.incrementAndGet() > capacity) {
            evict();
            return;
        }
        queue.add(item);
        scheduleDelivery();
    }

    private void scheduleDelivery() {
        if (delivering.compareAndSet(false, true)) {
            executor.execute(this::deliver);
        }
    }

    private void deliver() {
        try {
            Object item;
            while (!closed.get() && (item = queue.poll()) != null) {
                depth.decrementAndGet();
                List<Outbound> outbounds = renderOrNull(item);
                if (outbounds == null) {
                    return;
                }
                for (Outbound outbound : outbounds) {
                    send(outbound);
                }
            }
        } catch (IOException | IllegalStateException failure) {
            // The client has gone: a reset connection, or an emitter already completed.
            // Both are ordinary, and neither is worth more than a debug line.
            log.debug("Closing {} stream for {}: {}", channel, clientKey, failure.toString());
            closeQuietly();
        } finally {
            delivering.set(false);
            if (!closed.get() && !queue.isEmpty()) {
                scheduleDelivery();
            }
        }
    }

    /**
     * Rendering failing is a bug, not a disconnect, and is kept apart from the I/O
     * errors above for that reason: a client going away is routine and logged
     * quietly, while a renderer that throws would otherwise be reported the same way
     * and go unnoticed. The client is dropped so it reconnects rather than sitting
     * on a stream that has stopped producing.
     */
    private List<Outbound> renderOrNull(Object item) {
        try {
            return render(item);
        } catch (RuntimeException bug) {
            log.error("Could not render an event for the {} stream; dropping client {}", channel, clientKey, bug);
            close();
            return null;
        }
    }

    private List<Outbound> render(Object item) {
        if (item instanceof Outbound outbound) {
            return List.of(outbound);
        }
        if (item instanceof StreamEvent event) {
            return renderer.render(event);
        }
        return List.of(renderer.heartbeat((Heartbeat) item));
    }

    private void send(Outbound outbound) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(outbound.name()).data(outbound.data());
        if (outbound.id() != null) {
            event.id(outbound.id());
        }
        if (!firstEventSent) {
            event.reconnectTime(RETRY_MILLIS);
            firstEventSent = true;
        }
        emitter.send(event);
        metrics.eventSent(channel, outbound.name());
        metrics.bytesSent(channel, outbound.data().length());
    }

    /** Drops a client that has fallen too far behind. It reconnects and resumes. */
    private void evict() {
        if (closed.compareAndSet(false, true)) {
            metrics.evicted(channel);
            log.info("Dropping slow {} client {}: more than {} events waiting", channel, clientKey, capacity);
            onClosed.accept(this);
            executor.execute(emitter::complete);
        }
    }

    /** Ends the subscription from the server side, for shutdown. */
    void close() {
        if (closed.compareAndSet(false, true)) {
            onClosed.accept(this);
            executor.execute(emitter::complete);
        }
    }

    private void closeQuietly() {
        if (closed.compareAndSet(false, true)) {
            onClosed.accept(this);
        }
    }
}
