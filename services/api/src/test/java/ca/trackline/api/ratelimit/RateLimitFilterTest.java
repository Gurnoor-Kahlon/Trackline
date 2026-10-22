package ca.trackline.api.ratelimit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import ca.trackline.api.ratelimit.RateLimitProperties.Limit;
import ca.trackline.api.testsupport.MutableClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;

/** Which requests the filter counts, which it leaves alone, and what it does with a caller who has run out. */
class RateLimitFilterTest {

    private final List<Exception> refusals = new ArrayList<>();
    private RateLimitFilter filter;

    private final HandlerExceptionResolver recordingResolver = new HandlerExceptionResolver() {
        @Override
        public ModelAndView resolveException(
                HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
            refusals.add(exception);
            response.setStatus(429);
            return new ModelAndView();
        }
    };

    @BeforeEach
    void setUp() {
        filter = filterWith(new RateLimitProperties(true, new Limit(3, 60), new Limit(2, 60), new Limit(1, 60), 100));
    }

    private RateLimitFilter filterWith(RateLimitProperties properties) {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
        return new RateLimitFilter(properties, new TokenBuckets(clock, properties.maxTracked()), recordingResolver);
    }

    /** Sends a request and returns whether it reached the application. */
    private boolean send(String method, String path, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    private int sendUntilRefused(String method, String path, String address) throws Exception {
        int allowed = 0;
        while (send(method, path, address)) {
            allowed++;
            if (allowed > 1000) {
                throw new AssertionError("never refused");
            }
        }
        return allowed;
    }

    @Test
    void letsReadsThroughUpToTheirBurstThenRefuses() throws Exception {
        assertThat(sendUntilRefused("GET", "/api/v1/routes", "10.0.0.1")).isEqualTo(3);
        assertThat(refusals).hasSize(1).first().isInstanceOf(RateLimitedException.class);
    }

    @Test
    void holdsSearchToItsOwnSmallerBudget() throws Exception {
        assertThat(sendUntilRefused("GET", "/api/v1/search?q=kennedy", "10.0.0.1")).isEqualTo(2);
    }

    @Test
    void holdsWritesToTheirOwnBudget() throws Exception {
        assertThat(sendUntilRefused("PUT", "/api/v1/favourites/routes/501", "10.0.0.1")).isEqualTo(1);
        assertThat(sendUntilRefused("DELETE", "/api/v1/favourites/routes/41", "10.0.0.2")).isEqualTo(1);
    }

    @Test
    void keepsTheThreeBudgetsSeparateForOneCaller() throws Exception {
        sendUntilRefused("GET", "/api/v1/search?q=a", "10.0.0.1");

        assertThat(send("GET", "/api/v1/routes", "10.0.0.1")).as("a read after search is spent").isTrue();
        assertThat(send("PUT", "/api/v1/favourites/routes/501", "10.0.0.1")).as("a write after search is spent").isTrue();
    }

    @Test
    void countsEachAddressSeparately() throws Exception {
        sendUntilRefused("GET", "/api/v1/routes", "10.0.0.1");

        assertThat(send("GET", "/api/v1/routes", "10.0.0.1")).isFalse();
        assertThat(send("GET", "/api/v1/routes", "10.0.0.2")).isTrue();
    }

    @Test
    void neverCountsTheLiveStream() throws Exception {
        for (int request = 0; request < 50; request++) {
            assertThat(send("GET", "/api/v1/stream/vehicles", "10.0.0.1")).isTrue();
        }
        assertThat(refusals).isEmpty();
    }

    @Test
    void neverCountsSignInOrAccountCalls() throws Exception {
        // The web application relays these, so every visitor arrives from its address; they are limited per account instead.
        for (int request = 0; request < 50; request++) {
            assertThat(send("POST", "/api/v1/auth/login", "10.0.0.1")).isTrue();
            assertThat(send("POST", "/api/v1/auth/refresh", "10.0.0.1")).isTrue();
        }
    }

    @Test
    void neverCountsTheHealthEndpointAPlatformAsks() throws Exception {
        for (int request = 0; request < 50; request++) {
            assertThat(send("GET", "/actuator/health", "10.0.0.1")).isTrue();
        }
    }

    @Test
    void neverCountsABrowsersPreflight() throws Exception {
        for (int request = 0; request < 50; request++) {
            assertThat(send("OPTIONS", "/api/v1/routes", "10.0.0.1")).isTrue();
        }
        // And they have not used up the real requests that follow them.
        assertThat(sendUntilRefused("GET", "/api/v1/routes", "10.0.0.1")).isEqualTo(3);
    }

    @Test
    void doesNothingWhenSwitchedOff() throws Exception {
        filter = filterWith(new RateLimitProperties(false, new Limit(1, 1), new Limit(1, 1), new Limit(1, 1), 100));

        for (int request = 0; request < 20; request++) {
            assertThat(send("GET", "/api/v1/routes", "10.0.0.1")).isTrue();
        }
    }

    @Test
    void tellsTheCallerHowLongToWait() throws Exception {
        sendUntilRefused("GET", "/api/v1/routes", "10.0.0.1");

        RateLimitedException refusal = (RateLimitedException) refusals.get(0);
        // 60 a minute is one a second, and the bucket is empty.
        assertThat(refusal.retryAfter().toMillis()).isBetween(1L, 1000L);
    }
}
