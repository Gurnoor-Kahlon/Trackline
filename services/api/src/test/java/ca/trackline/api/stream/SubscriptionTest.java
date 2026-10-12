package ca.trackline.api.stream;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One client's queue and delivery: ordered, bounded, and unable to hurt anyone else.
 */
class SubscriptionTest {

    private ExecutorService executor;
    private SimpleMeterRegistry meters;
    private RecordingEmitter emitter;
    private AtomicInteger closedCallbacks;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        meters = new SimpleMeterRegistry();
        emitter = new RecordingEmitter();
        closedCallbacks = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        emitter.release();
        executor.shutdownNow();
    }

    /** Renders each event as "e<id>", so ordering is visible in what is written. */
    private static final class EchoRenderer implements StreamRenderer {
        @Override
        public List<Outbound> render(StreamEvent event) {
            return List.of(new Outbound(Long.toString(event.id()), "vehicles", "e" + event.id()));
        }

        @Override
        public Outbound heartbeat(Heartbeat heartbeat) {
            return new Outbound(null, "heartbeat", "beat");
        }
    }

    private Subscription subscription(int capacity, StreamRenderer renderer) {
        return new Subscription(
                "vehicles", "client-1", emitter, renderer, executor, capacity, new StreamMetrics(meters),
                closed -> closedCallbacks.incrementAndGet());
    }

    private static StreamEvent event(long id) {
        return new StreamEvent(
                id, id - 1, StreamEvent.Kind.DELTA, Instant.EPOCH, 0, 0, List.of(), List.of(), List.of());
    }

    @Test
    void eventsAreWrittenInTheOrderTheyWereOffered() {
        Subscription subscription = subscription(100, new EchoRenderer());

        for (long id = 1; id <= 50; id++) {
            subscription.offer(event(id));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() == 50);
        List<Long> order = emitter.sent().stream().map(sent -> Long.parseLong(sent.id())).toList();
        List<Long> expected = new ArrayList<>();
        for (long id = 1; id <= 50; id++) {
            expected.add(id);
        }
        // Delivery runs on another thread; the client must still see one total order.
        assertThat(order).isEqualTo(expected);
    }

    @Test
    void theFirstEventAsksTheClientToRetryPromptlyAndLaterOnesDoNot() {
        Subscription subscription = subscription(10, new EchoRenderer());

        subscription.offer(event(1));
        subscription.offer(event(2));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() == 2);
        assertThat(emitter.sent().get(0).retry()).isEqualTo(3000L);
        assertThat(emitter.sent().get(1).retry()).isNull();
    }

    @Test
    void aPrerenderedEventIsWrittenAsIs() {
        Subscription subscription = subscription(10, new EchoRenderer());

        subscription.offer(new Outbound("42", "snapshot", "{\"vehicles\":[]}"));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() == 1);
        assertThat(emitter.sent().get(0).name()).isEqualTo("snapshot");
        assertThat(emitter.sent().get(0).id()).isEqualTo("42");
        assertThat(emitter.sent().get(0).data()).isEqualTo("{\"vehicles\":[]}");
    }

    @Test
    void aHeartbeatIsRenderedByTheClientsOwnRendererAndCarriesNoId() {
        Subscription subscription = subscription(10, new EchoRenderer());

        subscription.offer(new StreamRenderer.Heartbeat(Instant.EPOCH, 5, Instant.EPOCH));

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() == 1);
        assertThat(emitter.sent().get(0).name()).isEqualTo("heartbeat");
        assertThat(emitter.sent().get(0).id()).isNull();
    }

    // --- the slow client ---------------------------------------------------------------------------

    @Test
    void aClientThatFallsTooFarBehindIsDroppedAndLeftToResume() {
        Subscription subscription = subscription(3, new EchoRenderer());
        emitter.blockWrites();   // its socket has stopped draining

        for (long id = 1; id <= 10; id++) {
            subscription.offer(event(id));
        }

        assertThat(subscription.isClosed()).isTrue();
        assertThat(closedCallbacks.get()).isEqualTo(1);
        assertThat(meters.counter("trackline.stream.evictions", "channel", "vehicles").count()).isEqualTo(1.0);
        // Ending the response is what makes the browser reconnect and resume.
        await().atMost(Duration.ofSeconds(5)).until(emitter::isCompleted);
    }

    @Test
    void aStalledClientNeverBlocksWhoeverIsOfferingEvents() {
        Subscription subscription = subscription(3, new EchoRenderer());
        emitter.blockWrites();

        long started = System.nanoTime();
        for (long id = 1; id <= 100; id++) {
            subscription.offer(event(id));
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // The ingestion thread is the one offering. If it waited on a client's socket,
        // one stalled phone would stop every other client getting updates.
        assertThat(elapsedMillis).isLessThan(500);
    }

    @Test
    void aClientThatCatchesUpBeforeTheLimitLosesNothing() {
        Subscription subscription = subscription(10, new EchoRenderer());
        emitter.blockWrites();

        for (long id = 1; id <= 5; id++) {
            subscription.offer(event(id));
        }
        emitter.release();

        await().atMost(Duration.ofSeconds(5)).until(() -> emitter.sent().size() == 5);
        assertThat(subscription.isClosed()).isFalse();
        assertThat(closedCallbacks.get()).isZero();
    }

    @Test
    void eventsOfferedAfterADropAreIgnored() {
        Subscription subscription = subscription(1, new EchoRenderer());
        emitter.blockWrites();
        for (long id = 1; id <= 5; id++) {
            subscription.offer(event(id));
        }
        emitter.release();

        subscription.offer(event(99));

        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(emitter.sent()).noneMatch(sent -> "99".equals(sent.id()));
    }

    // --- the client going away ---------------------------------------------------------------

    @Test
    void aFailedWriteMeansTheClientIsGoneAndIsRemovedOnce() {
        Subscription subscription = subscription(10, new EchoRenderer());
        emitter.failWrites(new IOException("Broken pipe"));

        subscription.offer(event(1));
        subscription.offer(event(2));

        await().atMost(Duration.ofSeconds(5)).until(subscription::isClosed);
        assertThat(closedCallbacks.get()).isEqualTo(1);
    }

    @Test
    void theContainerReportingADisconnectRemovesTheSubscription() {
        Subscription subscription = subscription(10, new EchoRenderer());

        emitter.simulateDisconnect();

        assertThat(subscription.isClosed()).isTrue();
        assertThat(closedCallbacks.get()).isEqualTo(1);
    }

    @Test
    void anErrorReportedByTheContainerRemovesTheSubscription() {
        Subscription subscription = subscription(10, new EchoRenderer());

        emitter.simulateError(new IOException("reset"));

        assertThat(subscription.isClosed()).isTrue();
    }

    @Test
    void aTimeoutEndsTheResponseSoTheClientReconnects() {
        Subscription subscription = subscription(10, new EchoRenderer());

        emitter.simulateTimeout();

        assertThat(subscription.isClosed()).isTrue();
        assertThat(emitter.isCompleted()).isTrue();
    }

    @Test
    void closingTwiceRemovesItOnlyOnce() {
        Subscription subscription = subscription(10, new EchoRenderer());

        emitter.simulateDisconnect();
        emitter.simulateDisconnect();
        subscription.close();

        assertThat(closedCallbacks.get()).isEqualTo(1);
    }

    @Test
    void closingFromTheServerEndsTheResponse() {
        Subscription subscription = subscription(10, new EchoRenderer());

        subscription.close();

        assertThat(subscription.isClosed()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(emitter::isCompleted);
    }

    // --- a bug is not a disconnect ---------------------------------------------------------------

    @Test
    void aRendererThatThrowsDropsTheClientSoItReconnectsInsteadOfSittingOnADeadStream() {
        StreamRenderer broken = new StreamRenderer() {
            @Override
            public List<Outbound> render(StreamEvent event) {
                throw new IllegalStateException("rendering bug");
            }

            @Override
            public Outbound heartbeat(Heartbeat heartbeat) {
                return new Outbound(null, "heartbeat", "beat");
            }
        };
        Subscription subscription = subscription(10, broken);

        subscription.offer(event(1));

        await().atMost(Duration.ofSeconds(5)).until(subscription::isClosed);
        await().atMost(Duration.ofSeconds(5)).until(emitter::isCompleted);
        assertThat(closedCallbacks.get()).isEqualTo(1);
    }
}
