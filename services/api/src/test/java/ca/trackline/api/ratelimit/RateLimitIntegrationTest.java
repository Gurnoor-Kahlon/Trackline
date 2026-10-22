package ca.trackline.api.ratelimit;

import ca.trackline.api.testsupport.PostgisIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limit as a caller meets it: the whole application with the limit switched on, turned down to three reads so the test is small.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "trackline.rate-limit.enabled=true",
    "trackline.rate-limit.reads.burst=3",
    "trackline.rate-limit.reads.per-minute=1",
})
class RateLimitIntegrationTest extends PostgisIntegrationTest {

    private static final String PAGE_ORIGIN = "http://localhost:3000";

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    void answersARequestOverTheLimitWithA429ProblemAndARetryAfter() throws Exception {
        for (int request = 0; request < 3; request++) {
            mvc.perform(get("/api/v1/network/status").with(from("198.51.100.1"))).andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/network/status").with(from("198.51.100.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("rate-limited")))
                // Like every other error: no stack trace and no exception type.
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void theRefusalCarriesTheCorsHeadersSoABrowserCanReadIt() throws Exception {
        for (int request = 0; request < 3; request++) {
            mvc.perform(get("/api/v1/network/status").with(from("198.51.100.2")).header("Origin", PAGE_ORIGIN))
                    .andExpect(status().isOk());
        }

        // Without these the page would see a failed network request and could not tell a limit from an outage.
        mvc.perform(get("/api/v1/network/status").with(from("198.51.100.2")).header("Origin", PAGE_ORIGIN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", PAGE_ORIGIN));
    }

    @Test
    void anotherAddressIsUnaffected() throws Exception {
        for (int request = 0; request < 4; request++) {
            mvc.perform(get("/api/v1/network/status").with(from("198.51.100.3")));
        }

        mvc.perform(get("/api/v1/network/status").with(from("198.51.100.4"))).andExpect(status().isOk());
    }

    @Test
    void neverRefusesTheHealthEndpoint() throws Exception {
        for (int request = 0; request < 20; request++) {
            mvc.perform(get("/actuator/health").with(from("198.51.100.5"))).andExpect(status().isOk());
        }
    }
}
