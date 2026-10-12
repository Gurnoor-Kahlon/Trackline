package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.trackline.api.common.error.TooManyConnectionsException;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
import ca.trackline.api.stream.RecordingEmitter.Sent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ca.trackline.api.stream.StreamTestData.LON;
import static ca.trackline.api.stream.StreamTestData.northMetres;
import static ca.trackline.api.stream.StreamTestData.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The hub's behaviour as a client experiences it, without HTTP.
 *
 * <p>Connections are recorded emitters, so each test can read exactly what a browser
 * would have received. The one that matters most is the join: a client connecting
 * while cycles are being published must end with the right picture, with no event
 * lost between its snapshot and its first delta and none delivered twice.
 */
class StreamHubTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant FEED = Instant.parse("2026-10-07T18:00:00Z");
    private static final long T0 = FEED.getEpochSecond();

    private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();
    private LiveVehicleReader fallback;
    private StreamHub hub;

    @BeforeEach
    void setUp() {
        fallback = mock(LiveVehicleReader.class);
        when(fallback.readAll()).thenReturn(LiveVehicles.nothingObserved());
        hub = hubWith(5, 100, 50);
    }

    @AfterEach
    void tearDown() {
        hub.destroy();
    }

    private StreamHub hubWith(int ringCapacity, int maxTotal, int maxPerClient) {
        return hubWith(ringCapacity, maxTotal, maxPerClient, 64);
    }

    private StreamHub hubWith(int ringCapacity, int maxTotal, int maxPerClient, int queueCapacity) {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(15), ringCapacity, maxTotal, maxPerClient,
                Duration.ofMinutes(30), queueCapacity, 5, Duration.ofSeconds(60));
        return new StreamHub(
                properties, fallback, Clock.systemUTC(), new StreamMetrics(new SimpleMeterRegistry()),
                timeout -> {
                    RecordingEmitter emitter = new RecordingEmitter();
                    emitters.add(emitter);
                    return emitter;
                });
    }

    private RecordingEmitter connect() {
        return connectWith(StreamFilter.none(), null);
    }

    private RecordingEmitter connectWith(StreamFilter filter, String lastEventId) {
        hub.connectVehicles(filter, lastEventId, "client");
        return emitters.get(emitters.size() - 1);
    }

    private static StreamFilter routes(String... ids) {
        return new StreamFilter(Set.of(ids), null);
    }

    /** A cycle, with vehicles a and b each moved north by the given metres. */
    private void publishFleet(long timestamp, double metres) {
        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(metres), LON, timestamp),
                        snapshot("b", "504", northMetres(metres + 500), LON, timestamp)),
                Instant.ofEpochSecond(timestamp));
    }

    private static JsonNode body(Sent sent) {
        try {
            return JSON.readTree(sent.data());
        } catch (Exception cause) {
            throw new AssertionError(cause);
        }
    }

    private void awaitEvents(RecordingEmitter emitter, int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() >= count);
    }

    private static List<String> vehicleIds(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get(0).asText()));
        return ids;
    }

    // --- joining ------------------------------------------------------------------------------------

    @Test
    void aClientConnectingBeforeAnyCycleIsGivenTheFallbackPictureNotAnEmptyMap() {
        when(fallback.readAll()).thenReturn(LiveVehicles.live(
                List.of(snapshot("a", "501", northMetres(0), LON, T0)), FEED));

        RecordingEmitter emitter = connect();

        awaitEvents(emitter, 1);
        assertThat(emitter.names()).containsExactly("snapshot");
        assertThat(vehicleIds(body(emitter.sent().get(0)).get("vehicles"))).containsExactly("a");
        // Nothing has been published, so the next event chains from "none yet".
        assertThat(emitter.sent().get(0).id()).isEqualTo("0");
    }

    @Test
    void aFailingFallbackStartsTheClientOnAnEmptySnapshotRatherThanAnError() {
        when(fallback.readAll()).thenThrow(new IllegalStateException("database down"));

        RecordingEmitter emitter = connect();

        awaitEvents(emitter, 1);
        assertThat(body(emitter.sent().get(0)).get("vehicles")).isEmpty();
    }

    @Test
    void aClientConnectingAfterACycleGetsTheCurrentFleetLabelledWithTheLatestEventId() {
        publishFleet(T0, 0);

        RecordingEmitter emitter = connect();

        awaitEvents(emitter, 1);
        Sent snapshot = emitter.sent().get(0);
        assertThat(snapshot.name()).isEqualTo("snapshot");
        assertThat(vehicleIds(body(snapshot).get("vehicles"))).containsExactlyInAnyOrder("a", "b");
        assertThat(snapshot.id()).isEqualTo(Long.toString(hub.latestEventId()));
    }

    @Test
    void theFirstCycleAfterStartupIsSentToConnectedClientsAsAFullSnapshot() {
        RecordingEmitter emitter = connect();
        awaitEvents(emitter, 1);   // the opening snapshot

        publishFleet(T0, 0);

        awaitEvents(emitter, 2);
        // There was nothing to compare against, so a client holding a picture from
        // before a restart must have it replaced, not patched.
        assertThat(emitter.names()).containsExactly("snapshot", "snapshot");
    }

    // --- changes --------------------------------------------------------------------------------------

    @Test
    void laterCyclesAreSentAsChangesContainingOnlyWhatMoved() {
        publishFleet(T0, 0);
        RecordingEmitter emitter = connect();
        awaitEvents(emitter, 1);

        // a moves 50 m; b does not move at all.
        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(50), LON, T0 + 20),
                        snapshot("b", "504", northMetres(500), LON, T0 + 20)),
                Instant.ofEpochSecond(T0 + 20));

        awaitEvents(emitter, 2);
        Sent delta = emitter.sent().get(1);
        assertThat(delta.name()).isEqualTo("vehicles");
        assertThat(vehicleIds(body(delta).get("changed"))).containsExactly("a");
    }

    @Test
    void aCycleWithNothingVisibleChangedPublishesNoEvent() {
        publishFleet(T0, 0);
        long idBefore = hub.latestEventId();
        RecordingEmitter emitter = connect();
        awaitEvents(emitter, 1);

        // Both vehicles report again at the same spots.
        publishFleet(T0 + 20, 0);

        assertThat(hub.latestEventId()).isEqualTo(idBefore);
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(emitter.sent()).hasSize(1);
    }

    @Test
    void aVehicleThatStopsReportingIsRemoved() {
        publishFleet(T0, 0);
        RecordingEmitter emitter = connect();
        awaitEvents(emitter, 1);

        hub.publish(List.of(snapshot("a", "501", northMetres(0), LON, T0 + 20)), Instant.ofEpochSecond(T0 + 20));

        awaitEvents(emitter, 2);
        assertThat(body(emitter.sent().get(1)).get("removed")).extracting(JsonNode::asText).containsExactly("b");
    }

    @Test
    void aRepeatedVehicleIdInOneCycleKeepsOnlyTheNewestReading() {
        RecordingEmitter emitter = connect();
        awaitEvents(emitter, 1);

        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(0), LON, T0 + 10),
                        snapshot("a", "504", northMetres(900), LON, T0)),
                Instant.ofEpochSecond(T0 + 10));

        awaitEvents(emitter, 2);
        JsonNode rows = body(emitter.sent().get(1)).get("vehicles");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get(1).asText()).isEqualTo("501");
    }

    // --- filters ----------------------------------------------------------------------------------------

    @Test
    void eachClientIsSentOnlyWhatItAskedFor() {
        publishFleet(T0, 0);
        RecordingEmitter follows501 = connectWith(routes("501"), null);
        RecordingEmitter followsEverything = connect();
        awaitEvents(follows501, 1);
        awaitEvents(followsEverything, 1);

        publishFleet(T0 + 20, 100);

        awaitEvents(follows501, 2);
        awaitEvents(followsEverything, 2);
        assertThat(vehicleIds(body(follows501.sent().get(1)).get("changed"))).containsExactly("a");
        assertThat(vehicleIds(body(followsEverything.sent().get(1)).get("changed"))).containsExactlyInAnyOrder("a", "b");
        // And the filtered client was never even told about b in its snapshot.
        assertThat(vehicleIds(body(follows501.sent().get(0)).get("vehicles"))).containsExactly("a");
    }

    @Test
    void aFilteredClientIsNotSentAnEventThatChangesNothingItCanSee() {
        publishFleet(T0, 0);
        RecordingEmitter follows501 = connectWith(routes("501"), null);
        awaitEvents(follows501, 1);

        // Only b, which is on 504, moves.
        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(0), LON, T0 + 20),
                        snapshot("b", "504", northMetres(900), LON, T0 + 20)),
                Instant.ofEpochSecond(T0 + 20));

        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(follows501.sent()).hasSize(1);
    }

    // --- resuming -----------------------------------------------------------------------------------------

    @Test
    void aClientThatDroppedResumesWithExactlyTheEventsItMissedAndNoSnapshot() {
        publishFleet(T0, 0);
        RecordingEmitter first = connect();
        awaitEvents(first, 1);
        String lastSeen = first.sent().get(0).id();

        publishFleet(T0 + 20, 100);
        publishFleet(T0 + 40, 200);

        RecordingEmitter resumed = connectWith(StreamFilter.none(), lastSeen);
        awaitEvents(resumed, 3);

        // Two missed changes, then a heartbeat saying "you are current"; never a snapshot.
        assertThat(resumed.names()).containsExactly("vehicles", "vehicles", "heartbeat");
        assertThat(resumed.sent().get(0).id()).isNotEqualTo(lastSeen);
    }

    @Test
    void aClientAlreadyCurrentIsToldSoWithoutBeingResent() {
        publishFleet(T0, 0);
        RecordingEmitter first = connect();
        awaitEvents(first, 1);

        RecordingEmitter resumed = connectWith(StreamFilter.none(), first.sent().get(0).id());

        awaitEvents(resumed, 1);
        assertThat(resumed.names()).containsExactly("heartbeat");
    }

    @Test
    void aClientThatFellOutOfTheRingIsGivenAFreshSnapshotNotAHole() {
        hub.destroy();
        hub = hubWith(3, 100, 50);
        publishFleet(T0, 0);
        RecordingEmitter first = connect();
        awaitEvents(first, 1);
        String stale = first.sent().get(0).id();

        // Five more events into a ring of three: the one after "stale" has been dropped.
        for (int cycle = 1; cycle <= 5; cycle++) {
            publishFleet(T0 + 20L * cycle, 100.0 * cycle);
        }

        RecordingEmitter resumed = connectWith(StreamFilter.none(), stale);

        awaitEvents(resumed, 1);
        assertThat(resumed.names()).containsExactly("snapshot");
    }

    @Test
    void anIdThatMeansNothingToThisServerGetsAFreshSnapshot() {
        publishFleet(T0, 0);

        assertThat(firstEventAfterConnecting("999")).isEqualTo("snapshot");
        assertThat(firstEventAfterConnecting("not-a-number")).isEqualTo("snapshot");
        assertThat(firstEventAfterConnecting("")).isEqualTo("snapshot");
    }

    private String firstEventAfterConnecting(String lastEventId) {
        RecordingEmitter emitter = connectWith(StreamFilter.none(), lastEventId);
        awaitEvents(emitter, 1);
        return emitter.names().get(0);
    }

    @Test
    void anIdFromBeforeARestartCannotResumeOntoTheNewRun() {
        publishFleet(T0, 0);
        RecordingEmitter before = connect();
        awaitEvents(before, 1);
        String idFromTheOldRun = before.sent().get(0).id();

        hub.reset();
        publishFleet(T0 + 20, 0);

        assertThat(firstEventAfterConnecting(idFromTheOldRun)).isEqualTo("snapshot");
    }

    // --- joining while cycles are being published ------------------------------------------------

    /**
     * A client built the way the frontend is: replace on snapshot, patch on change.
     * If the join were not atomic its picture would diverge from the hub's.
     */
    private static final class MiniClient {
        final Map<String, List<Double>> vehicles = new HashMap<>();
        final List<Long> eventIds = new ArrayList<>();

        void apply(Sent sent) {
            JsonNode body = body(sent);
            if (sent.id() != null) {
                eventIds.add(Long.parseLong(sent.id()));
            }
            switch (sent.name()) {
                case "snapshot" -> {
                    vehicles.clear();
                    body.get("vehicles").forEach(row -> put(row));
                }
                case "vehicles" -> {
                    body.get("changed").forEach(row -> put(row));
                    body.get("removed").forEach(id -> vehicles.remove(id.asText()));
                }
                default -> { }
            }
        }

        private void put(JsonNode row) {
            vehicles.put(row.get(0).asText(), List.of(row.get(2).asDouble(), row.get(7).asDouble()));
        }
    }

    @Test
    void clientsJoiningWhileCyclesArePublishedMissNothingAndSeeNothingTwice() throws Exception {
        // A thousand cycles in a tight loop is far faster than the real one per 20 s, so
        // this hub's queue holds the whole burst. Dropping a client that cannot keep
        // up is covered on its own; this test is about the join.
        hub.destroy();
        hub = hubWith(2_000, 200, 200, 5_000);
        publishFleet(T0, 0);

        // Connected throughout, so it received every event: the reference for what a
        // joiner ought to see after its snapshot.
        RecordingEmitter reference = connect();
        AtomicBoolean publishing = new AtomicBoolean(true);
        int[] lastCycle = new int[1];

        Thread publisher = Thread.ofVirtual().start(() -> {
            for (int cycle = 1; cycle <= 1_000; cycle++) {
                // 40 m a cycle, so every vehicle is sent every cycle.
                publishFleet(T0 + 20L * cycle, 40.0 * cycle);
                lastCycle[0] = cycle;
            }
            publishing.set(false);
        });

        // Many joiners at different instants, so some land in any window the join leaves open.
        List<RecordingEmitter> joiners = new ArrayList<>();
        while (publishing.get() && joiners.size() < 150) {
            joiners.add(connect());
            Thread.onSpinWait();
        }
        publisher.join();

        await().atMost(Duration.ofSeconds(20)).until(() -> reference.sent().stream()
                .anyMatch(sent -> sent.id() != null && Long.parseLong(sent.id()) == hub.latestEventId()));
        assertThat(joiners).as("joiners that connected mid-stream").hasSizeGreaterThan(5);

        List<Long> everyEvent = reference.sent().stream()
                .filter(sent -> sent.id() != null).map(sent -> Long.parseLong(sent.id())).toList();
        double expectedLat = Math.round(northMetres(40.0 * lastCycle[0]) * 1e6) / 1e6;

        for (RecordingEmitter joiner : joiners) {
            await().atMost(Duration.ofSeconds(20)).until(() -> joiner.sent().stream()
                    .anyMatch(sent -> sent.id() != null && Long.parseLong(sent.id()) == hub.latestEventId()));

            List<Sent> received = joiner.sent();
            long snapshotId = Long.parseLong(received.get(0).id());
            List<Long> afterSnapshot = received.stream().skip(1)
                    .filter(sent -> sent.id() != null).map(sent -> Long.parseLong(sent.id())).toList();

            // Every event after the snapshot, exactly once, in order. A joiner registered
            // late would be missing some; one registered early would repeat some.
            List<Long> expected = everyEvent.stream().filter(id -> id > snapshotId).toList();
            assertThat(afterSnapshot).isEqualTo(expected);

            MiniClient client = new MiniClient();
            received.forEach(client::apply);
            assertThat(client.vehicles.get("a").get(0)).isEqualTo(expectedLat);
        }
    }

    // --- limits and cleanup ----------------------------------------------------------------------------

    @Test
    void connectionsBeyondTheLimitAreRefusedAndRoomReturnsWhenOneLeaves() {
        hub.destroy();
        hub = hubWith(5, 3, 50);

        connect();
        connect();
        connect();

        assertThatThrownBy(this::connect).isInstanceOf(TooManyConnectionsException.class);
        assertThat(hub.subscriberCount()).isEqualTo(3);

        emitters.get(0).simulateDisconnect();
        assertThat(hub.subscriberCount()).isEqualTo(2);
        connect();
        assertThat(hub.subscriberCount()).isEqualTo(3);
    }

    @Test
    void oneAddressCannotTakeEveryConnection() {
        hub.destroy();
        hub = hubWith(5, 100, 2);

        hub.connectVehicles(StreamFilter.none(), null, "10.0.0.1");
        hub.connectVehicles(StreamFilter.none(), null, "10.0.0.1");

        assertThatThrownBy(() -> hub.connectVehicles(StreamFilter.none(), null, "10.0.0.1"))
                .isInstanceOf(TooManyConnectionsException.class)
                .hasMessageContaining("2");
        // Someone else is unaffected.
        hub.connectVehicles(StreamFilter.none(), null, "10.0.0.2");
        assertThat(hub.subscriberCount()).isEqualTo(3);
    }

    @Test
    void theSubscriberCountFollowsConnectionsAndDisconnections() {
        assertThat(hub.subscriberCount()).isZero();

        RecordingEmitter first = connect();
        connect();
        hub.connectNetwork("client");

        assertThat(hub.subscriberCount()).isEqualTo(3);
        assertThat(hub.subscriberCount("vehicles")).isEqualTo(2);
        assertThat(hub.subscriberCount("network")).isEqualTo(1);

        first.simulateDisconnect();
        assertThat(hub.subscriberCount("vehicles")).isEqualTo(1);
    }

    @Test
    void shuttingDownEndsEveryConnection() {
        RecordingEmitter one = connect();
        RecordingEmitter two = connect();

        hub.destroy();

        await().atMost(Duration.ofSeconds(5)).until(() -> one.isCompleted() && two.isCompleted());
        assertThat(hub.subscriberCount()).isZero();
    }

    // --- heartbeat and the network stream -------------------------------------------------------------

    @Test
    void aHeartbeatReachesEveryClientAndCarriesTheFeedTimeAndLatestEvent() {
        publishFleet(T0, 0);
        RecordingEmitter a = connect();
        RecordingEmitter b = connectWith(routes("501"), null);
        awaitEvents(a, 1);
        awaitEvents(b, 1);

        hub.heartbeat();

        awaitEvents(a, 2);
        awaitEvents(b, 2);
        for (RecordingEmitter emitter : List.of(a, b)) {
            Sent beat = emitter.sent().get(1);
            assertThat(beat.name()).isEqualTo("heartbeat");
            assertThat(beat.id()).isNull();
            assertThat(body(beat).get("feed").get("timestamp").asText()).isEqualTo(Instant.ofEpochSecond(T0).toString());
            assertThat(body(beat).get("latestEventId").asLong()).isEqualTo(hub.latestEventId());
        }
    }

    @Test
    void theNetworkStreamGivesCurrentCountsAtOnceAndFreshOnesEachCycle() {
        publishFleet(T0, 0);
        hub.connectNetwork("client");
        RecordingEmitter emitter = emitters.get(emitters.size() - 1);
        awaitEvents(emitter, 1);

        assertThat(emitter.names()).containsExactly("network");
        assertThat(body(emitter.sent().get(0)).get("vehiclesTracked").asInt()).isEqualTo(2);
        assertThat(body(emitter.sent().get(0)).get("routesReporting").asInt()).isEqualTo(2);
        assertThat(body(emitter.sent().get(0)).get("activeAlerts").isNull()).isTrue();

        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(80), LON, T0 + 20),
                        snapshot("b", "501", northMetres(80), LON, T0 + 20),
                        snapshot("c", "501", northMetres(80), LON, T0 + 20),
                        snapshot("d", null, northMetres(80), LON, T0 + 20)),
                Instant.ofEpochSecond(T0 + 20));

        awaitEvents(emitter, 2);
        // Four vehicles, but only one route among them: the unassigned one has none.
        assertThat(body(emitter.sent().get(1)).get("vehiclesTracked").asInt()).isEqualTo(4);
        assertThat(body(emitter.sent().get(1)).get("routesReporting").asInt()).isEqualTo(1);
    }

    @Test
    void aVehicleStreamClientNeverReceivesNetworkEventsAndViceVersa() {
        RecordingEmitter vehicles = connect();
        hub.connectNetwork("client");
        RecordingEmitter network = emitters.get(emitters.size() - 1);
        awaitEvents(vehicles, 1);
        awaitEvents(network, 1);

        publishFleet(T0, 0);

        awaitEvents(vehicles, 2);
        awaitEvents(network, 2);
        assertThat(vehicles.names()).containsExactly("snapshot", "snapshot");
        assertThat(network.names()).containsExactly("network", "network");
    }
}
