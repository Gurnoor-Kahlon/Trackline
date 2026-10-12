package ca.trackline.api.network;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.List;

import ca.trackline.api.alerts.ActiveAlerts;
import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
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

    @MockitoBean
    private LiveVehicleReader vehicles;

    @MockitoBean
    private ActiveAlerts activeAlerts;

    private static VehicleSnapshot vehicle(String id, String route) {
        return new VehicleSnapshot(id, route, null, 43.65, -79.38, 90f, 5f, null, null, null, null,
                Instant.parse("2026-09-27T17:59:40Z"));
    }

    @Test
    void statusReportsNothingObservedWhenNothingHasBeenIngested() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(vehicles.readAll()).thenReturn(LiveVehicles.nothingObserved());
        // A mock would otherwise answer 0 for an Integer, which is a measurement.
        when(activeAlerts.current()).thenReturn(null);

        mockMvc.perform(get("/api/v1/network/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.vehiclesTracked").value(0))
                .andExpect(jsonPath("$.data.routesReporting").value(0))
                // Not zero: no alert count is known, and zero would claim one was measured.
                .andExpect(jsonPath("$.data.activeAlerts").isEmpty())
                // NO_DATA is what stops a client reading those zeros as a measurement.
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.generatedAt").value("2026-09-27T18:00:00Z"))
                .andExpect(jsonPath("$.meta.stale").value(true))
                .andExpect(jsonPath("$.meta.degraded").value(false))
                .andExpect(jsonPath("$.meta.feedTimestamp").isEmpty())
                .andExpect(jsonPath("$.meta.ageSeconds").isEmpty());
    }

    @Test
    void statusCountsTheLiveSnapshotAndNamesItsAge() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        // Four vehicles on two routes, and one with no route, which is a vehicle but
        // belongs to no route.
        when(vehicles.readAll()).thenReturn(LiveVehicles.live(
                List.of(vehicle("a", "501"), vehicle("b", "501"), vehicle("c", "504"), vehicle("d", "504"),
                        vehicle("e", null)),
                Instant.parse("2026-09-27T17:59:40Z")));
        when(activeAlerts.current()).thenReturn(null);

        mockMvc.perform(get("/api/v1/network/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehiclesTracked").value(5))
                .andExpect(jsonPath("$.data.routesReporting").value(2))
                .andExpect(jsonPath("$.data.activeAlerts").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.ageSeconds").value(20))
                .andExpect(jsonPath("$.meta.stale").value(false));
    }

    @Test
    void statusReportsTheOpenAlertCountEvenBeforeAnyVehicleHasBeenSeen() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(vehicles.readAll()).thenReturn(LiveVehicles.nothingObserved());
        when(activeAlerts.current()).thenReturn(11);

        mockMvc.perform(get("/api/v1/network/status"))
                .andExpect(status().isOk())
                // Alerts are their own feed: no vehicles says nothing about whether they are known.
                .andExpect(jsonPath("$.data.activeAlerts").value(11))
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"));
    }

    @Test
    void statusReportsAZeroAlertCountAsZeroWhenItIsMeasured() throws Exception {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(vehicles.readAll()).thenReturn(LiveVehicles.live(
                List.of(vehicle("a", "501")), Instant.parse("2026-09-27T17:59:40Z")));
        when(activeAlerts.current()).thenReturn(0);

        mockMvc.perform(get("/api/v1/network/status"))
                .andExpect(jsonPath("$.data.activeAlerts").value(0));
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
