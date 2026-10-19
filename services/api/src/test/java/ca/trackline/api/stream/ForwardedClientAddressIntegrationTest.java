package ca.trackline.api.stream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.testsupport.TestContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a load balancer, which visitor is which.
 *
 * <p>The live stream limits how many connections one address may hold, so that a client reconnecting in a loop cannot exhaust a
 * small server. Behind a load balancer every connection arrives from the balancer, and without the production profile's
 * {@code forward-headers-strategy} the limit would count the whole site as one visitor and allow it a handful of streams. This
 * runs the real application under that profile over real HTTP, with the limit turned down to two so the test is small, and
 * checks both halves: visitors a trusted proxy names are told apart, and a connection with no forwarded address is counted
 * against the address it came from.
 *
 * <p>The test client connects from the loopback address, which Tomcat treats as an internal proxy by default, as it would the
 * balancer's private address in a virtual network.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "TRACKLINE_CORS_ALLOWED_ORIGINS=https://app.trackline.test",
            "trackline.auth.jwt-secret=a-test-secret-for-the-forwarded-address-test-0123456789",
            "trackline.gtfs.realtime.enabled=false",
            "trackline.history.enabled=false",
            "trackline.stream.max-subscribers-per-client=2",
            // A stream the test has closed is only noticed when the server next writes to it, and a graceful shutdown (which the
            // production profile has) waits for every open one. Beating every second and giving shutdown three keeps the build from
            // standing for thirty seconds at the end of the run.
            "trackline.stream.heartbeat-interval=1s",
            "spring.lifecycle.timeout-per-shutdown-phase=3s",
            "spring.datasource.hikari.maximum-pool-size=5",
            "spring.datasource.hikari.minimum-idle=1",
        })
@ActiveProfiles("prod")
class ForwardedClientAddressIntegrationTest {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", TestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> TestContainers.REDIS.getMappedPort(TestContainers.REDIS_PORT));
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<InputStream> open = new ArrayList<>();

    @AfterEach
    void closeStreams() {
        for (InputStream stream : open) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Closing is what frees the slot; there is nothing to do if it was already gone.
            }
        }
    }

    @Test
    void tellsVisitorsApartByTheAddressTheProxyForwarded() throws Exception {
        // Six connections, three more than the limit of two would allow from one address, each from a different visitor.
        for (int visitor = 1; visitor <= 6; visitor++) {
            assertThat(connect("203.0.113." + visitor)).as("visitor %d", visitor).isEqualTo(200);
        }
    }

    @Test
    void stillLimitsOneForwardedVisitor() throws Exception {
        assertThat(connect("203.0.113.50")).isEqualTo(200);
        assertThat(connect("203.0.113.50")).isEqualTo(200);

        assertThat(connect("203.0.113.50")).isEqualTo(429);
    }

    @Test
    void doesNotLetAVisitorBorrowAnotherAddressByPrependingOne() throws Exception {
        // The balancer appends the real address to whatever the client sent, so the client's own claim is on the left of it and
        // the right-most address that is not a trusted proxy is the one that counts. Here that is 203.0.113.60, whatever was
        // claimed before it.
        assertThat(connect("198.51.100.1, 203.0.113.60")).isEqualTo(200);
        assertThat(connect("198.51.100.2, 203.0.113.60")).isEqualTo(200);

        assertThat(connect("198.51.100.3, 203.0.113.60")).isEqualTo(429);
    }

    @Test
    void countsAConnectionWithNoForwardedAddressAgainstTheAddressItCameFrom() throws Exception {
        assertThat(connect(null)).isEqualTo(200);
        assertThat(connect(null)).isEqualTo(200);

        assertThat(connect(null)).isEqualTo(429);
    }

    /** Opens a stream and returns the status it was answered with, keeping an admitted one open until the test ends. */
    private int connect(String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/stream/network"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream");
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        open.add(response.body());
        return response.statusCode();
    }
}
