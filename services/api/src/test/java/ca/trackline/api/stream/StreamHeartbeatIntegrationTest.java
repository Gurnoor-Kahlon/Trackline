package ca.trackline.api.stream;

import java.time.Duration;

import ca.trackline.api.stream.SseClient.Event;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heartbeat on its real timer.
 *
 * <p>Separate from the other HTTP tests because it runs with a one-second interval,
 * and a heartbeat landing between the events those tests wait for would make them
 * flaky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "trackline.stream.heartbeat-interval=1s")
class StreamHeartbeatIntegrationTest extends PostgisIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private StreamHub hub;

    @BeforeEach
    void setUp() {
        hub.reset();
    }

    @AfterEach
    void tearDown() {
        hub.reset();
    }

    @Test
    void anIdleConnectionIsWrittenToOnTheConfiguredInterval() throws Exception {
        try (SseClient client = SseClient.open("http://localhost:" + port + "/api/v1/stream/vehicles")) {
            assertThat(client.expectNext(Duration.ofSeconds(5)).name()).isEqualTo("snapshot");

            // Nothing is being published, so anything that arrives is the timer.
            Event first = client.expectNext(Duration.ofSeconds(5));
            Event second = client.expectNext(Duration.ofSeconds(5));

            assertThat(first.name()).isEqualTo("heartbeat");
            assertThat(second.name()).isEqualTo("heartbeat");
            // Beats carry no id, so they can never move where a client resumes from.
            assertThat(first.id()).isNull();
            // About a second apart.
            double gapMillis = (second.receivedAtNanos() - first.receivedAtNanos()) / 1e6;
            assertThat(gapMillis).isBetween(500.0, 2_500.0);
        }
    }
}
