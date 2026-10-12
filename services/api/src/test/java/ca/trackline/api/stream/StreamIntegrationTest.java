package ca.trackline.api.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ca.trackline.api.alerts.ActiveAlerts;
import ca.trackline.api.stream.SseClient.Event;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static ca.trackline.api.stream.StreamTestData.LON;
import static ca.trackline.api.stream.StreamTestData.northMetres;
import static ca.trackline.api.stream.StreamTestData.snapshot;
import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertRoute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The streams over a real socket: what a browser's EventSource actually experiences.
 *
 * <p>Everything about delivery is covered without HTTP elsewhere. What only a real
 * connection can show is here: the response headers, how errors come back to a client
 * that asked for an event stream, CORS, and what happens on the server when the
 * client closes the connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamIntegrationTest extends PostgisIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long T0 = Instant.parse("2026-10-07T18:00:00Z").getEpochSecond();

    @LocalServerPort
    private int port;

    @Autowired
    private StreamHub hub;

    @Autowired
    private ActiveAlerts activeAlerts;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<SseClient> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hub.reset();
        clear(jdbc);
        long feed = insertFeedVersion(jdbc);
        insertRoute(jdbc, feed, "501", "501", "Queen", 0);
        insertRoute(jdbc, feed, "504", "504", "King", 0);
    }

    @AfterEach
    void tearDown() {
        open.forEach(SseClient::close);
        hub.reset();
        clear(jdbc);
    }

    private String url(String pathAndQuery) {
        return "http://localhost:" + port + "/api/v1" + pathAndQuery;
    }

    private SseClient connect(String pathAndQuery) throws Exception {
        return connect(pathAndQuery, Map.of());
    }

    private SseClient connect(String pathAndQuery, Map<String, String> headers) throws Exception {
        SseClient client = SseClient.open(url(pathAndQuery), headers);
        open.add(client);
        return client;
    }

    /** The next event that carries data, skipping the heartbeats that may interleave. */
    private static Event nextData(SseClient client) throws InterruptedException {
        for (int attempts = 0; attempts < 20; attempts++) {
            Event event = client.expectNext(WAIT);
            if (!event.name().equals("heartbeat")) {
                return event;
            }
        }
        throw new AssertionError("Only heartbeats arrived: " + client.names());
    }

    private void publish(long timestamp, double metres) {
        hub.publish(
                List.of(
                        snapshot("a", "501", northMetres(metres), LON, timestamp),
                        snapshot("b", "504", northMetres(metres + 500), LON, timestamp)),
                Instant.ofEpochSecond(timestamp));
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get(0).asText()));
        return ids;
    }

    // --- the connection ---------------------------------------------------------------------------

    @Test
    void theResponseHasTheHeadersALiveStreamNeeds() throws Exception {
        SseClient client = connect("/stream/vehicles");

        assertThat(client.status()).isEqualTo(200);
        assertThat(client.header("Content-Type")).startsWith("text/event-stream");
        // Without these a proxy may cache the stream or buffer it into lumps.
        assertThat(client.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(client.header("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void theFirstEventIsASnapshotAndChangesFollowOverTheWire() throws Exception {
        publish(T0, 0);
        SseClient client = connect("/stream/vehicles");

        Event snapshot = nextData(client);
        assertThat(snapshot.name()).isEqualTo("snapshot");
        assertThat(ids(snapshot.json().get("vehicles"))).containsExactlyInAnyOrder("a", "b");
        assertThat(snapshot.json().get("fields")).hasSize(8);

        publish(T0 + 20, 100);

        Event change = nextData(client);
        assertThat(change.name()).isEqualTo("vehicles");
        assertThat(ids(change.json().get("changed"))).containsExactlyInAnyOrder("a", "b");
        assertThat(Long.parseLong(change.id())).isGreaterThan(Long.parseLong(snapshot.id()));
    }

    @Test
    void aRouteFilterInTheQueryStringIsApplied() throws Exception {
        publish(T0, 0);
        SseClient client = connect("/stream/vehicles?routes=504");

        assertThat(ids(nextData(client).json().get("vehicles"))).containsExactly("b");

        publish(T0 + 20, 100);
        assertThat(ids(nextData(client).json().get("changed"))).containsExactly("b");
    }

    @Test
    void aBoundingBoxInTheQueryStringIsApplied() throws Exception {
        publish(T0, 0);
        // A box around vehicle a, which sits at the base latitude; b is 500 m north.
        String bbox = "-79.39,43.64,-79.37," + northMetres(250);
        SseClient client = connect("/stream/vehicles?bbox=" + bbox);

        assertThat(ids(nextData(client).json().get("vehicles"))).containsExactly("a");
    }

    @Test
    void resumingWithTheLastEventIdHeaderGetsOnlyWhatWasMissed() throws Exception {
        publish(T0, 0);
        String lastSeen;
        try (SseClient first = connect("/stream/vehicles")) {
            lastSeen = nextData(first).id();
        }
        publish(T0 + 20, 100);

        SseClient resumed = connect("/stream/vehicles", Map.of("Last-Event-ID", lastSeen));

        Event missed = nextData(resumed);
        assertThat(missed.name()).isEqualTo("vehicles");
        assertThat(ids(missed.json().get("changed"))).containsExactlyInAnyOrder("a", "b");
        assertThat(resumed.names()).doesNotContain("snapshot");
    }

    @Test
    void resumingWithTheQueryParameterWorksForAClientThatCannotSetHeaders() throws Exception {
        publish(T0, 0);
        String lastSeen;
        try (SseClient first = connect("/stream/vehicles")) {
            lastSeen = nextData(first).id();
        }
        publish(T0 + 20, 100);

        SseClient resumed = connect("/stream/vehicles?lastEventId=" + lastSeen);

        assertThat(nextData(resumed).name()).isEqualTo("vehicles");
    }

    @Test
    void aMadeUpLastEventIdIsAnsweredWithASnapshotNotAnError() throws Exception {
        publish(T0, 0);

        SseClient client = connect("/stream/vehicles", Map.of("Last-Event-ID", "12345"));

        assertThat(client.status()).isEqualTo(200);
        assertThat(nextData(client).name()).isEqualTo("snapshot");
    }

    // --- errors reach a client that asked for an event stream -------------------------------

    @Test
    void anUnknownRouteIsAProblemDetailNotAnEmptyOrBrokenStream() throws Exception {
        // EventSource sends Accept: text/event-stream, which an error body cannot
        // satisfy. The error must still arrive as a readable problem, not a 406.
        SseClient client = connect("/stream/vehicles?routes=9999");

        assertThat(client.status()).isEqualTo(404);
        assertThat(client.errorBody()).contains("Resource not found").contains("9999");
    }

    @Test
    void malformedInputsAreRefusedWithAReasonBeforeAnythingIsOpened() throws Exception {
        assertThat(connect("/stream/vehicles?routes=bad!id").status()).isEqualTo(400);
        assertThat(connect("/stream/vehicles?bbox=1,2,3").status()).isEqualTo(400);
        assertThat(connect("/stream/vehicles?bbox=-81,40,-79,44").status()).isEqualTo(400);

        List<String> tooMany = new ArrayList<>();
        for (int route = 0; route < 21; route++) {
            tooMany.add("r" + route);
        }
        SseClient client = connect("/stream/vehicles?routes=" + String.join(",", tooMany));
        assertThat(client.status()).isEqualTo(400);
        assertThat(client.errorBody()).contains("at most 20");
        assertThat(hub.subscriberCount()).isZero();
    }

    @Test
    void anAddressAtItsConnectionLimitIsTurnedAwayWithRetryAfter() throws Exception {
        for (int connection = 0; connection < 20; connection++) {
            assertThat(connect("/stream/vehicles").status()).isEqualTo(200);
        }

        SseClient refused = connect("/stream/vehicles");

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.header("Retry-After")).isEqualTo("5");
        assertThat(refused.errorBody()).contains("Too many live connections");
        assertThat(hub.subscriberCount()).isEqualTo(20);
    }

    // --- the browser's view: CORS -----------------------------------------------------------------

    @Test
    void theFrontendOriginIsAllowedToOpenTheStream() throws Exception {
        SseClient client = connect("/stream/vehicles", Map.of("Origin", "http://localhost:3000"));

        assertThat(client.status()).isEqualTo(200);
        assertThat(client.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
    }

    @Test
    void anotherOriginIsRefused() throws Exception {
        SseClient client = connect("/stream/vehicles", Map.of("Origin", "https://evil.example"));

        assertThat(client.status()).isEqualTo(403);
    }

    @Test
    void aPreflightThatAsksToSendLastEventIdIsAllowed() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(URI.create(url("/stream/vehicles")))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "last-event-id")
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(preflight, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElse("")).containsIgnoringCase("Last-Event-ID");
    }

    // --- the client going away ---------------------------------------------------------------------

    @Test
    void aClosedConnectionIsRemovedFromTheRegistry() throws Exception {
        SseClient client = connect("/stream/vehicles");
        nextData(client);
        assertThat(hub.subscriberCount("vehicles")).isEqualTo(1);

        client.close();

        // The server only learns a peer has gone when a write fails, so beats are sent
        // until it notices, the same way the scheduled heartbeat discovers it.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            hub.heartbeat();
            return hub.subscriberCount("vehicles") == 0;
        });
    }

    @Test
    void manyClientsComingAndGoingLeaveNothingBehind() throws Exception {
        for (int round = 0; round < 15; round++) {
            SseClient client = connect("/stream/vehicles");
            nextData(client);
            client.close();
        }

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            hub.heartbeat();
            return hub.subscriberCount() == 0;
        });
    }

    // --- the network stream -------------------------------------------------------------------------

    @Test
    void theNetworkStreamGivesCountsAndThenFreshOnesEachCycle() throws Exception {
        publish(T0, 0);
        SseClient client = connect("/stream/network");

        Event first = nextData(client);
        assertThat(first.name()).isEqualTo("network");
        assertThat(first.json().get("vehiclesTracked").asInt()).isEqualTo(2);
        assertThat(first.json().get("routesReporting").asInt()).isEqualTo(2);
        // Whatever the application knows about alerts, the stream says: a number once alerts
        // have been ingested, null before. Shared state decides which, so assert the agreement.
        Integer known = activeAlerts.current();
        if (known == null) {
            assertThat(first.json().get("activeAlerts").isNull()).isTrue();
        } else {
            assertThat(first.json().get("activeAlerts").asInt()).isEqualTo(known);
        }

        publish(T0 + 20, 100);
        assertThat(nextData(client).name()).isEqualTo("network");
    }
}
