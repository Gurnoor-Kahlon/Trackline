package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
import ca.trackline.api.stream.DeltaCalculator.Delta;
import ca.trackline.api.stream.StreamRenderer.Heartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Turns each ingestion cycle into events and delivers them to whoever is connected.
 *
 * <p>Everything that touches the shared picture happens under one lock: working out
 * what changed, recording it for resuming clients, handing it to every connected
 * client, and connecting a new one. That is what makes joining safe. A client that
 * connects gets a snapshot of the picture as it stood at one instant and is then
 * registered before the lock is released, so no event can fall between the snapshot
 * and its first delta, and none can be delivered twice.
 *
 * <p>The lock is cheap to hold because nothing under it waits on a network. Handing
 * an event to a client only queues it; each client's connection is written by its own
 * delivery task.
 */
@Service
public class StreamHub implements DisposableBean {

    static final String VEHICLES = "vehicles";
    static final String NETWORK = "network";

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    private final StreamProperties properties;
    private final LiveVehicleReader fallbackReader;
    private final Clock clock;
    private final StreamMetrics metrics;
    private final DeltaCalculator calculator;
    private final SubscriptionRegistry registry;
    private final EventRing ring;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final LongFunction<SseEmitter> emitters;

    private final ReentrantLock lock = new ReentrantLock();

    /** The fleet as the latest cycle reported it. What a joining client is given. */
    private Map<String, Tracked> latest = Map.of();

    /** The fleet as clients were last told, which suppressed changes leave behind. */
    private Map<String, Tracked> lastSent = Map.of();

    private Instant feedTimestamp;

    /** False until the first cycle after startup, which is sent as a full snapshot. */
    private volatile boolean initialized;

    @Autowired
    public StreamHub(
            StreamProperties properties, LiveVehicleReader fallbackReader, Clock clock, StreamMetrics metrics) {
        this(properties, fallbackReader, clock, metrics, SseEmitter::new);
    }

    /**
     * @param emitters makes the emitter for a connection, given its timeout in
     *                 milliseconds. A seam for tests, which substitute one that records
     *                 what would have been written instead of needing an HTTP server.
     */
    StreamHub(
            StreamProperties properties,
            LiveVehicleReader fallbackReader,
            Clock clock,
            StreamMetrics metrics,
            LongFunction<SseEmitter> emitters) {
        this.emitters = emitters;
        this.properties = properties;
        this.fallbackReader = fallbackReader;
        this.clock = clock;
        this.metrics = metrics;
        this.calculator = new DeltaCalculator(properties.moveThresholdMetres(), properties.keepaliveInterval().toSeconds());
        this.registry = new SubscriptionRegistry(properties.maxSubscribers(), properties.maxSubscribersPerClient());
        this.ring = new EventRing(properties.ringCapacity());

        metrics.bindSubscriberGauge(VEHICLES, () -> registry.size(VEHICLES));
        metrics.bindSubscriberGauge(NETWORK, () -> registry.size(NETWORK));
    }

    /**
     * Publishes one ingestion cycle.
     *
     * <p>The first cycle after startup is sent as a full snapshot, because there is
     * nothing to compare against and clients holding a picture from before the
     * restart must have it replaced, not patched.
     */
    public void publish(List<VehicleSnapshot> vehicles, Instant feed) {
        Map<String, Tracked> current = distinct(vehicles);

        lock.lock();
        try {
            long previousId = ring.latestId();
            long id = ring.nextId(Instant.now(clock).toEpochMilli());
            int routes = routesReporting(current);
            StreamEvent event;

            if (!initialized) {
                event = new StreamEvent(
                        id, previousId, StreamEvent.Kind.SNAPSHOT, feed, current.size(), routes,
                        List.copyOf(current.values()), List.of(), List.of());
                lastSent = new HashMap<>(current);
            } else {
                Delta delta = calculator.compute(lastSent, latest, current);
                lastSent = delta.nextSent();
                if (delta.isEmpty()) {
                    // Nothing a client could see has changed. No event is better than an
                    // empty one; the heartbeat still tells clients the feed is current.
                    latest = current;
                    feedTimestamp = feed;
                    return;
                }
                event = new StreamEvent(
                        id, previousId, StreamEvent.Kind.DELTA, feed, current.size(), routes,
                        List.of(), delta.changed(), delta.removed());
            }

            latest = current;
            feedTimestamp = feed;
            initialized = true;
            ring.append(event);
            for (Subscription subscription : registry.all()) {
                subscription.offer(event);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Opens the vehicle stream for one client.
     *
     * @param lastEventId the id the client last received, from the Last-Event-ID
     *                    header or its query-parameter equivalent; null for a new client
     * @throws ca.trackline.api.common.error.TooManyConnectionsException at capacity
     */
    public SseEmitter connectVehicles(StreamFilter filter, String lastEventId, String clientKey) {
        // Read outside the lock: this can reach the database, which must not be
        // waited on while holding up every other publish and connect.
        Fallback fallback = initialized ? null : readFallback();

        lock.lock();
        try {
            registry.admit(clientKey);
            SseEmitter emitter = emitters.apply(properties.emitterTimeout().toMillis());
            VehicleRenderer renderer = new VehicleRenderer(filter, clock);
            Subscription subscription = newSubscription(VEHICLES, clientKey, emitter, renderer);

            Optional<List<StreamEvent>> replay = parse(lastEventId).flatMap(ring::replayAfter);
            if (replay.isPresent()) {
                replay.get().forEach(subscription::offer);
                // Tells the client it is current even when there was nothing to replay,
                // and that it has resumed rather than restarted.
                subscription.offer(heartbeatNow());
                metrics.resumed(VEHICLES, "resumed");
            } else {
                Map<String, Tracked> fleet = initialized ? latest : fallback.fleet();
                Instant feed = initialized ? feedTimestamp : fallback.feedTimestamp();
                subscription.offer(renderer.snapshot(ring.latestId(), feed, fleet.values()));
                metrics.resumed(VEHICLES, "snapshot");
            }

            registry.add(subscription);
            return emitter;
        } finally {
            lock.unlock();
        }
    }

    /** Opens the network-status stream: the current counts now, then fresh ones each cycle. */
    public SseEmitter connectNetwork(String clientKey) {
        Fallback fallback = initialized ? null : readFallback();

        lock.lock();
        try {
            registry.admit(clientKey);
            SseEmitter emitter = emitters.apply(properties.emitterTimeout().toMillis());
            NetworkRenderer renderer = new NetworkRenderer(clock);
            Subscription subscription = newSubscription(NETWORK, clientKey, emitter, renderer);

            Map<String, Tracked> fleet = initialized ? latest : fallback.fleet();
            Instant feed = initialized ? feedTimestamp : fallback.feedTimestamp();
            subscription.offer(renderer.status(feed, fleet.size(), routesReporting(fleet)));

            registry.add(subscription);
            return emitter;
        } finally {
            lock.unlock();
        }
    }

    /** Writes to every idle connection, to keep proxies from closing it and to find dead peers. */
    public void heartbeat() {
        Heartbeat heartbeat;
        List<Subscription> everyone;
        lock.lock();
        try {
            heartbeat = heartbeatNow();
            everyone = registry.all();
        } finally {
            lock.unlock();
        }
        everyone.forEach(subscription -> subscription.offer(heartbeat));
    }

    public int subscriberCount() {
        return registry.size();
    }

    public int subscriberCount(String channel) {
        return registry.size(channel);
    }

    public long latestEventId() {
        return ring.latestId();
    }

    @Override
    public void destroy() {
        registry.all().forEach(Subscription::close);
        executor.shutdown();
    }

    /** Forgets everything. For tests that need a hub as it is at startup. */
    void reset() {
        lock.lock();
        try {
            registry.all().forEach(Subscription::close);
            latest = Map.of();
            lastSent = Map.of();
            feedTimestamp = null;
            initialized = false;
            ring.clear();
        } finally {
            lock.unlock();
        }
    }

    private Subscription newSubscription(String channel, String clientKey, SseEmitter emitter, StreamRenderer renderer) {
        return new Subscription(
                channel, clientKey, emitter, renderer, executor, properties.queueCapacity(), metrics, registry::remove);
    }

    private Heartbeat heartbeatNow() {
        return new Heartbeat(feedTimestamp, ring.latestId(), Instant.now(clock));
    }

    /**
     * What to show a client that connects before this instance has ingested anything.
     * Comes from the live-state tiers, so a restarted instance does not greet its
     * first client with an empty map while the cache holds a perfectly good picture.
     */
    private Fallback readFallback() {
        try {
            LiveVehicles live = fallbackReader.readAll();
            return new Fallback(distinct(live.vehicles()), live.feedTimestamp());
        } catch (RuntimeException failure) {
            log.warn("Could not read a starting snapshot for a new stream client: {}", failure.toString());
            return new Fallback(Map.of(), null);
        }
    }

    /** The newest reading per vehicle id; the feed has been seen to repeat one. */
    private static Map<String, Tracked> distinct(List<VehicleSnapshot> vehicles) {
        Map<String, Tracked> byId = new LinkedHashMap<>();
        for (VehicleSnapshot vehicle : vehicles) {
            Tracked tracked = Tracked.from(vehicle);
            byId.merge(tracked.id(), tracked, (kept, incoming) -> incoming.timestamp() > kept.timestamp() ? incoming : kept);
        }
        return byId;
    }

    private static int routesReporting(Map<String, Tracked> fleet) {
        return (int) fleet.values().stream().map(Tracked::route).filter(route -> route != null).distinct().count();
    }

    private static Optional<Long> parse(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException notAnId) {
            // A header the client made up is treated as no header at all.
            return Optional.empty();
        }
    }

    private record Fallback(Map<String, Tracked> fleet, Instant feedTimestamp) {
    }
}
