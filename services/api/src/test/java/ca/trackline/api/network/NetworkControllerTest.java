package ca.trackline.api.network;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import ca.trackline.api.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkController.class)
@Import({NetworkStatusService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class NetworkControllerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-27T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Clock clock;

    @Test
    void statusReturnsTheEnvelopeWithHonestlyUnobservedCounts() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        mockMvc.perform(get("/api/v1/network/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.vehiclesTracked").value(0))
                .andExpect(jsonPath("$.data.routesReporting").value(0))
                .andExpect(jsonPath("$.data.activeAlerts").value(0))
                // NO_DATA is what stops a client reading those zeros as a measurement.
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.generatedAt").value("2026-09-27T18:00:00Z"))
                .andExpect(jsonPath("$.meta.stale").value(true))
                .andExpect(jsonPath("$.meta.degraded").value(false))
                .andExpect(jsonPath("$.meta.feedTimestamp").isEmpty())
                .andExpect(jsonPath("$.meta.ageSeconds").isEmpty());
    }

    @Test
    void unknownPathUnderTheApiReturnsProblemDetailNotHtml() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        mockMvc.perform(get("/api/v1/network/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Endpoint not found"))
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/endpoint-not-found"))
                .andExpect(jsonPath("$.detail").value("No endpoint matches GET /api/v1/network/does-not-exist."))
                // Spring's own wording describes its static-resource resolver; the
                // API must not expose that as an explanation.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("static resource"))));
    }
}
