package ca.trackline.api.stream;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.stream.SseClient.Event;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static ca.trackline.api.stream.StreamTestData.LON;
import static ca.trackline.api.stream.StreamTestData.northMetres;
import static ca.trackline.api.stream.StreamTestData.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the stream behaves with a real fleet and many clients.
 *
 * <p>The figures it logs are the ones published in docs/performance.md. Its
 * assertions are deliberately loose: they catch the stream being broken, a client
 * missing an event or latency going from milliseconds to seconds, not the exact speed
 * of whatever machine runs the build. The fleet is the size and rhythm of the real
 * one: about 1,500 vehicles, of which about two thirds change each cycle.
 *
 * <p>Clients and server share one JVM and one machine, so the latencies include the
 * clients' own parsing. That makes them an upper bound on what a client sees, not a
 * model of a network.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "trackline.stream.max-subscribers-per-client=50")
class StreamFanoutMeasurementTest extends PostgisIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StreamFanoutMeasurementTest.class);

    private static final int CLIENTS = 25;
    private static final int CYCLES = 30;
    private static final int FLEET = 1_500;
    private static final int MOVING = 1_000;
    private static final long T0 = Instant.parse("2026-10-07T18:00:00Z").getEpochSecond();

    @LocalServerPort
    private int port;

    @Autowired
    private StreamHub hub;

    private final List<SseClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hub.reset();
    }

    @AfterEach
    void tearDown() {
        clients.forEach(SseClient::close);
        hub.reset();
    }

    /**
     * The fleet at a given cycle: the first MOVING vehicles have moved 30 m since the
     * last cycle and the rest are standing where they were, as in the real feed.
     */
    private List<VehicleSnapshot> fleet(int cycle) {
        List<VehicleSnapshot> vehicles = new ArrayList<>(FLEET);
        for (int index = 0; index < FLEET; index++) {
            double metres = index < MOVING ? 30.0 * cycle : 0;
            // A grid, so vehicles are spread over the city and not stacked on one spot.
            double lat = northMetres(metres + (index / 40) * 400);
            double lon = LON + (index % 40) * 0.002;
            String route = Integer.toString(1 + index % 100);
            vehicles.add(snapshot("v" + index, route, lat, lon, T0 + 20L * cycle));
        }
        return vehicles;
    }

    private static double percentile(List<Double> sorted, double fraction) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * fraction)));
    }

    /**
     * What keeping the replay ring costs. Measured as the growth in live heap after a
     * full ring of real-sized events, with collections forced either side, so it is an
     * estimate with a few hundred kilobytes of noise rather than an exact figure.
     */
    @Test
    void aFullReplayRingOfRealSizedEventsCostsThisMuchMemory() {
        hub.publish(fleet(0), Instant.ofEpochSecond(T0));
        long before = liveHeapBytes();

        int events = 30;   // the configured ring capacity
        for (int cycle = 1; cycle <= events; cycle++) {
            hub.publish(fleet(cycle), Instant.ofEpochSecond(T0 + 20L * cycle));
        }
        long after = liveHeapBytes();

        double megabytes = (after - before) / 1_048_576.0;
        log.info("FANOUT ring of {} events holds about {} MB of heap", events, String.format("%.1f", megabytes));

        // Loose: an unbounded buffer would be hundreds of megabytes here.
        assertThat(megabytes).isLessThan(100.0);
    }

    private static long liveHeapBytes() {
        for (int collection = 0; collection < 3; collection++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    @Test
    void twentyFiveClientsEachReceiveEveryCycleOfARealSizedFleet() throws Exception {
        hub.publish(fleet(0), Instant.ofEpochSecond(T0));

        for (int client = 0; client < CLIENTS; client++) {
            clients.add(SseClient.open("http://localhost:" + port + "/api/v1/stream/vehicles"));
        }

        int snapshotBytes = 0;
        for (SseClient client : clients) {
            Event opening = client.expectNext(Duration.ofSeconds(10));
            assertThat(opening.name()).isEqualTo("snapshot");
            snapshotBytes = opening.data().length();
        }

        List<Double> publishMillis = new ArrayList<>();
        List<Double> deliveryMillis = new ArrayList<>();
        List<Integer> eventBytes = new ArrayList<>();

        for (int cycle = 1; cycle <= CYCLES; cycle++) {
            List<VehicleSnapshot> vehicles = fleet(cycle);

            long started = System.nanoTime();
            hub.publish(vehicles, Instant.ofEpochSecond(T0 + 20L * cycle));
            publishMillis.add((System.nanoTime() - started) / 1e6);

            for (SseClient client : clients) {
                Event event = client.expectNext(Duration.ofSeconds(10));
                assertThat(event.name()).isEqualTo("vehicles");
                deliveryMillis.add((event.receivedAtNanos() - started) / 1e6);
                if (client == clients.get(0)) {
                    eventBytes.add(event.data().length());
                }
            }
            // The real cadence is seconds, so give each cycle's work time to finish and
            // measure it alone, not queued behind the one before.
            Thread.sleep(100);
        }

        Collections.sort(publishMillis);
        Collections.sort(deliveryMillis);
        Collections.sort(eventBytes);

        log.info("FANOUT clients={} fleet={} moving={} cycles={}", CLIENTS, FLEET, MOVING, CYCLES);
        log.info("FANOUT snapshot bytes={}  delta bytes median={} max={}",
                snapshotBytes, eventBytes.get(eventBytes.size() / 2), eventBytes.get(eventBytes.size() - 1));
        log.info("FANOUT publish() ms p50={} p95={} max={}",
                String.format("%.2f", percentile(publishMillis, 0.5)),
                String.format("%.2f", percentile(publishMillis, 0.95)),
                String.format("%.2f", publishMillis.get(publishMillis.size() - 1)));
        log.info("FANOUT publish->received ms p50={} p95={} max={}  ({} deliveries)",
                String.format("%.1f", percentile(deliveryMillis, 0.5)),
                String.format("%.1f", percentile(deliveryMillis, 0.95)),
                String.format("%.1f", deliveryMillis.get(deliveryMillis.size() - 1)),
                deliveryMillis.size());

        // Every client received every cycle: no event lost to the fanout.
        assertThat(deliveryMillis).hasSize(CLIENTS * CYCLES);
        // Nobody was dropped for being slow.
        assertThat(hub.subscriberCount("vehicles")).isEqualTo(CLIENTS);
        // Loose: the stream is broken if this goes from milliseconds to seconds.
        assertThat(percentile(deliveryMillis, 0.95)).isLessThan(2_000.0);
        assertThat(percentile(publishMillis, 0.95)).isLessThan(250.0);
    }
}
