package ca.trackline.api.config;

import ca.trackline.api.testsupport.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application as it runs in production: the {@code prod} profile on its own, with the settings a deployment supplies
 * through the environment and nothing from the {@code test} profile.
 *
 * <p>Every other integration test runs under {@code test}, so nothing else would notice if the production profile stopped
 * loading. It did, once: a key written twice in {@code application-prod.yml} made the application refuse to start with that
 * profile, and no test, build or review caught it, because none of them ever used it. It was found by running the packaged
 * jar. This class is that run, kept.
 *
 * <p>It also holds the production profile to what it promises: only the health endpoint is exposed, the manual import
 * endpoint is not registered, and the browser origin comes from the environment and is the only one allowed.
 */
@SpringBootTest(
        properties = {
            // What a deployment supplies, as environment variables would.
            "TRACKLINE_CORS_ALLOWED_ORIGINS=https://app.trackline.test",
            "trackline.auth.jwt-secret=a-test-secret-for-the-production-profile-test-0123456789",
            // Not the profile's business, and a poller would reach the real TTC from a build.
            "trackline.gtfs.realtime.enabled=false",
            "trackline.history.enabled=false",
            "spring.datasource.hikari.maximum-pool-size=5",
            "spring.datasource.hikari.minimum-idle=1",
        })
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionProfileIntegrationTest {

    private static final String WEB_ORIGIN = "https://app.trackline.test";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", TestContainers.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> TestContainers.REDIS.getMappedPort(TestContainers.REDIS_PORT));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Environment environment;

    @Test
    void startsUnderTheProductionProfileAlone() {
        assertThat(environment.getActiveProfiles()).containsExactly("prod");
    }

    @Test
    void takesTheBrowserOriginFromTheEnvironment() {
        assertThat(environment.getProperty("trackline.cors.allowed-origins")).isEqualTo(WEB_ORIGIN);
    }

    @Test
    void takesTheClientAddressFromTheProxyInFrontOfIt() {
        // Without this, the live stream's limit per address counts a whole site behind a load balancer as one visitor.
        // ForwardedClientAddressIntegrationTest shows what it does.
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void keepsTheSettingsThatSitBesideTheOriginInTheProfile() {
        // The two live under one `trackline:` key. Written under two, YAML refuses to load the file at all, which is
        // the failure this class exists to catch; reading both back shows that neither was lost to the other.
        assertThat(environment.getProperty("trackline.gtfs.static.admin-endpoint", Boolean.class)).isFalse();
    }

    @Test
    void reportsHealthy() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesNothingBeyondHealth() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
    }

    @Test
    void showsNoComponentDetailToAnAnonymousCaller() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void doesNotRegisterTheManualImportEndpoint() throws Exception {
        // It has no administrator to protect it, so in production it must not exist rather than merely be hard to find.
        mvc.perform(post("/api/v1/admin/gtfs/import")).andExpect(status().isNotFound());
    }

    @Test
    void allowsTheConfiguredOriginAndNoOtherToReadTheApi() throws Exception {
        mvc.perform(get("/api/v1/network/status").header("Origin", WEB_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEB_ORIGIN));
        mvc.perform(get("/api/v1/network/status").header("Origin", "https://elsewhere.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void answersAPreflightFromTheConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/v1/favourites")
                        .header("Origin", WEB_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", WEB_ORIGIN));
        mvc.perform(options("/api/v1/favourites")
                        .header("Origin", "https://elsewhere.test")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
