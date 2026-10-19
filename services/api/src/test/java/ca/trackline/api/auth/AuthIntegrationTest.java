package ca.trackline.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import ca.trackline.api.testsupport.MutableClock;
import ca.trackline.api.testsupport.PostgisIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The real application, filter chain and database, with a clock the test moves.
 *
 * <p>Everything here goes through the HTTP layer and the real security configuration, so a rule that only holds in a unit
 * test cannot pass. The addresses and passwords are made up for the test and mean nothing outside it.
 */
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.TestClock.class)
abstract class AuthIntegrationTest extends PostgisIntegrationTest {

    static final Instant START = Instant.parse("2026-10-20T12:00:00Z");

    /** The one clock the application sees in these tests. */
    static final MutableClock CLOCK = new MutableClock(START);

    /** A password that satisfies the rules, for a test that is not about passwords. */
    static final String PASSWORD = "a-test-passphrase-for-this-run";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetTimeAndAccounts() {
        CLOCK.set(START);
        jdbc.execute("TRUNCATE users CASCADE");
    }

    /** An address nobody else in the run uses, so the in-memory throttle never carries over between tests. */
    protected static String newEmail() {
        return "person-" + SEQUENCE.incrementAndGet() + "-" + Long.toHexString(System.nanoTime()) + "@example.test";
    }

    protected static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    protected MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Registers an account and returns the response body with its tokens. */
    protected JsonNode register(String email, String password) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/register", credentials(email, password))).andReturn();
        if (result.getResponse().getStatus() != 201) {
            throw new AssertionError("registration failed: " + result.getResponse().getStatus());
        }
        return json.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode register(String email) throws Exception {
        return register(email, PASSWORD);
    }

    protected static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asText();
    }
}
