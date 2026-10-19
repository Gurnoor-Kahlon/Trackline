package ca.trackline.api.vehicles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.gtfs.realtime.VehicleSnapshot;
import ca.trackline.api.livestate.LiveVehicleReader;
import ca.trackline.api.livestate.LiveVehicles;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the live vehicle endpoints.
 *
 * <p>What matters to a client is here: that the envelope reports the real age and
 * provenance of what it was given, that a bad bounding box is a client error rather
 * than a server one, and that an unknown route is a 404 rather than an empty list
 * implying the route exists and is idle.
 */
@WebMvcTest(controllers = VehicleController.class)
@Import(GlobalExceptionHandler.class)
// These are about what each controller returns. Whether an address needs a token is checked once, against
// the real filter chain, in PublicEndpointsStayOpenTest.
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class VehicleApiContractTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveVehicleReader reader;

    @MockitoBean
    private RouteQueryRepository routes;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void fixTheClock() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routes.exists(anyString())).thenReturn(true);
    }

    @Test
    void liveVehiclesCarryTheirAgeAndProvenance() throws Exception {
        when(reader.readAll()).thenReturn(LiveVehicles.live(List.of(vehicle("1234", "501", NOW.minusSeconds(12))),
                NOW.minusSeconds(20)));

        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.vehicles[0].vehicleId").value("1234"))
                // Per-vehicle age as well as snapshot age: a parked vehicle can be
                // minutes old inside an otherwise current snapshot.
                .andExpect(jsonPath("$.data.vehicles[0].ageSeconds").value(12))
                .andExpect(jsonPath("$.data.oldestReadingSeconds").value(12))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.ageSeconds").value(20))
                .andExpect(jsonPath("$.meta.degraded").value(false));
    }

    @Test
    void aDegradedAnswerSaysSo() throws Exception {
        when(reader.readAll())
                .thenReturn(LiveVehicles.fromDatabase(List.of(vehicle("1234", "501", NOW.minusSeconds(40))),
                        NOW.minusSeconds(40)));

        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.source").value("DB_FALLBACK"))
                .andExpect(jsonPath("$.meta.degraded").value(true));
    }

    @Test
    void nothingIngestedIsNoDataRatherThanAnEmptyFleet() throws Exception {
        when(reader.readAll()).thenReturn(LiveVehicles.nothingObserved());

        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.ageSeconds").isEmpty())
                .andExpect(jsonPath("$.data.oldestReadingSeconds").isEmpty());
    }

    @Test
    void absentFieldsAreNullRatherThanZero() throws Exception {
        VehicleSnapshot sparse = new VehicleSnapshot(
                "9999", null, null, 43.65, -79.38, null, null, null, null, null, null, NOW);
        when(reader.readAll()).thenReturn(LiveVehicles.live(List.of(sparse), NOW));

        mockMvc.perform(get("/api/v1/vehicles"))
                .andExpect(status().isOk())
                // A speed of zero and an unreported speed are different claims.
                .andExpect(jsonPath("$.data.vehicles[0].sourceSpeed").isEmpty())
                .andExpect(jsonPath("$.data.vehicles[0].bearing").isEmpty())
                .andExpect(jsonPath("$.data.vehicles[0].routeId").isEmpty());
    }

    @Test
    void filteringByRouteDelegatesToTheReader() throws Exception {
        when(reader.readForRoute("501"))
                .thenReturn(LiveVehicles.live(List.of(vehicle("1234", "501", NOW)), NOW));

        mockMvc.perform(get("/api/v1/vehicles").param("route", "501"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicles[0].routeId").value("501"));

        mockMvc.perform(get("/api/v1/routes/501/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void anUnknownRouteIsNotFoundRatherThanAnEmptyList() throws Exception {
        when(routes.exists("9999")).thenReturn(false);

        // An empty list would say the route exists and nothing is running on it.
        mockMvc.perform(get("/api/v1/routes/9999/vehicles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Route"));

        mockMvc.perform(get("/api/v1/vehicles").param("route", "9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSubwayRouteWithNoLiveVehiclesIsAnEmptySetNotAnError() throws Exception {
        when(reader.readForRoute("1")).thenReturn(LiveVehicles.live(List.of(), NOW.minusSeconds(15)));

        // The TTC publishes no subway vehicle positions, so this is the normal and
        // permanent answer for Lines 1, 2 and 4.
        mockMvc.perform(get("/api/v1/routes/1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(0))
                .andExpect(jsonPath("$.meta.source").value("LIVE"));
    }

    @Test
    void aBoundingBoxFiltersByArea() throws Exception {
        when(reader.readInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(LiveVehicles.live(List.of(vehicle("1234", "501", NOW)), NOW));

        mockMvc.perform(get("/api/v1/vehicles").param("bbox", "-79.45,43.60,-79.30,43.70"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    void aMalformedBoundingBoxIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles").param("bbox", "not-a-box"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.violations[0].parameter").value("bbox"));

        mockMvc.perform(get("/api/v1/vehicles").param("bbox", "-79.45,43.60,-79.30"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBoundingBoxWithItsCornersReversedIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles").param("bbox", "-79.30,43.70,-79.45,43.60"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("wrong order")));
    }

    @Test
    void anAbsurdlyLargeBoundingBoxIsRejected() throws Exception {
        // Asking for a box spanning the continent is asking for the whole fleet by
        // another name, and should say so by omitting the box.
        mockMvc.perform(get("/api/v1/vehicles").param("bbox", "-100.0,30.0,-60.0,60.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("degrees")));
    }

    private static VehicleSnapshot vehicle(String id, String routeId, Instant reportedAt) {
        return new VehicleSnapshot(
                id, routeId, null, 43.65, -79.38, 90f, 5f, (short) 1, 3, "1234", (short) 1, reportedAt);
    }
}
