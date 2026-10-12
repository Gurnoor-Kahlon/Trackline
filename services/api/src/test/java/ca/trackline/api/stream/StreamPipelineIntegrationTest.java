package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import ca.trackline.api.gtfs.realtime.RealtimeFeed;
import ca.trackline.api.ingestion.IngestionCycleResult;
import ca.trackline.api.ingestion.IngestionCycleService;
import ca.trackline.api.persistence.entity.IngestionRunStatus;
import ca.trackline.api.stream.SseClient.Event;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static ca.trackline.api.testsupport.FeedFixtures.bytes;
import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real ingestion cycle reaching a real client over HTTP.
 *
 * <p>The unit and hub tests prove the stream works when fed. This proves it is fed:
 * that the ingestion cycle hands each cycle to the hub, and that doing so cannot
 * cost the cycle anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamPipelineIntegrationTest extends PostgisIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(8);

    private static final java.util.concurrent.atomic.AtomicLong HEADER_OFFSET =
            new java.util.concurrent.atomic.AtomicLong();

    private static WireMockServer feedServer;

    @LocalServerPort
    private int port;

    @Autowired
    private IngestionCycleService cycleService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private StreamHub hub;

    /** Fixed to when the fixtures were recorded; see the ingestion integration tests. */
    @MockitoBean
    private Clock clock;

    @BeforeAll
    static void startFeedServer() {
        feedServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        feedServer.start();
    }

    @AfterAll
    static void stopFeedServer() {
        feedServer.stop();
    }

    @DynamicPropertySource
    static void pointAtTheFakeFeed(DynamicPropertyRegistry registry) {
        registry.add("trackline.gtfs.realtime.base-url", () -> feedServer.baseUrl() + "/gtfsrt");
    }

    @BeforeEach
    void setUp() {
        Instant recordedAt = headerTimestampOf(bytes("vehicles.pb"));
        Mockito.when(clock.instant()).thenReturn(recordedAt);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        feedServer.resetAll();
        clear(jdbc);
        hub.reset();
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(hub);
        hub.reset();
        clear(jdbc);
    }

    private static Instant headerTimestampOf(byte[] payload) {
        try {
            return Instant.ofEpochSecond(
                    com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(payload).getHeader().getTimestamp());
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    /**
     * The recorded payload under a header time of its own.
     *
     * <p>The cycle service skips a payload whose header it has already seen, and keeps
     * that memory between tests. Giving every call a header no earlier call used means
     * a cycle is always processed, without reaching into the service to clear its state.
     */
    private static byte[] withNewHeader(byte[] payload) {
        try {
            var message = com.google.transit.realtime.GtfsRealtime.FeedMessage.parseFrom(payload);
            long timestamp = message.getHeader().getTimestamp() + HEADER_OFFSET.addAndGet(30);
            return message.toBuilder()
                    .setHeader(message.getHeader().toBuilder().setTimestamp(timestamp))
                    .build()
                    .toByteArray();
        } catch (com.google.protobuf.InvalidProtocolBufferException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private static void serve(byte[] body) {
        feedServer.stubFor(WireMock.get("/gtfsrt/vehicles")
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/x-google-protobuf")
                        .withBody(body)));
    }

    private SseClient connect() throws Exception {
        return SseClient.open("http://localhost:" + port + "/api/v1/stream/vehicles");
    }

    @Test
    void aCycleOfTheRealFeedReachesAConnectedClientAsASnapshot() throws Exception {
        try (SseClient client = connect()) {
            assertThat(client.expectNext(WAIT).name()).isEqualTo("snapshot");   // the opening picture

            serve(withNewHeader(bytes("vehicles.pb")));
            IngestionCycleResult result = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
            assertThat(result.status()).isEqualTo(IngestionRunStatus.SUCCESS);

            Event event = client.expectNext(WAIT);
            // The first cycle since startup replaces the client's picture outright.
            assertThat(event.name()).isEqualTo("snapshot");
            // The recorded feed holds 40 vehicles; every one is streamed, routeless ones too.
            assertThat(event.json().get("vehicles")).hasSize(40);
            assertThat(event.json().get("fields").get(0).asText()).isEqualTo("id");
        }
    }

    @Test
    void aFeedThatSaysNothingNewProducesNoEvent() throws Exception {
        serve(withNewHeader(bytes("vehicles.pb")));
        cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        try (SseClient client = connect()) {
            assertThat(client.expectNext(WAIT).name()).isEqualTo("snapshot");

            // New header, identical vehicles: processed by ingestion, but nothing a map
            // could show has changed, so nothing is sent.
            feedServer.resetAll();
            serve(withNewHeader(bytes("vehicles.pb")));
            cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

            assertThat(client.next(Duration.ofSeconds(1))).isNull();
        }
    }

    @Test
    void aStreamFailureCostsTheCycleNothing() {
        Mockito.doThrow(new IllegalStateException("stream hub exploded")).when(hub).publish(Mockito.anyList(), Mockito.any());
        byte[] payload = withNewHeader(bytes("vehicles.pb"));
        serve(payload);

        IngestionCycleResult first = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);

        // Streaming is a view over data already stored and cached, so its failure must
        // not unwind past the rest of the cycle.
        assertThat(first.status()).isEqualTo(IngestionRunStatus.SUCCESS);
        Mockito.verify(hub).publish(Mockito.anyList(), Mockito.any());

        // The cycle's bookkeeping after the publish step still ran. It records the
        // header it has seen, and the same payload again is therefore recognised as
        // nothing new. Had the exception unwound past it, this would be processed again.
        IngestionCycleResult second = cycleService.runCycle(RealtimeFeed.VEHICLE_POSITIONS);
        assertThat(second.status()).isEqualTo(IngestionRunStatus.NO_CHANGE);

        Integer runs = jdbc.queryForObject(
                "SELECT count(*) FROM ingestion_runs WHERE status = 'SUCCESS' AND feed = 'VEHICLE_POSITIONS'", Integer.class);
        assertThat(runs).isEqualTo(1);
    }
}
