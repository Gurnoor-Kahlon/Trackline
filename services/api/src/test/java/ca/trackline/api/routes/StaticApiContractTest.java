package ca.trackline.api.routes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.persistence.query.SearchQueryRepository;
import ca.trackline.api.persistence.query.StopQueryRepository;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.search.SearchController;
import ca.trackline.api.stops.StopController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the static endpoints: envelope, caps and not-found.
 *
 * <p>Data is mocked here on purpose. What is under test is the behaviour a client
 * sees — that an over-large page size is rejected as a client error naming the
 * parameter, not swallowed or returned as a server fault — and that holds
 * regardless of what is in the database.
 */
@WebMvcTest(controllers = {RouteController.class, StopController.class, SearchController.class})
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class StaticApiContractTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-01T18:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteQueryRepository routes;

    @MockitoBean
    private StopQueryRepository stops;

    @MockitoBean
    private SearchQueryRepository search;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void fixTheClock() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void routeListIsWrappedInTheEnvelopeAndMarkedStatic() throws Exception {
        when(routes.countRoutes(any(), any())).thenReturn(1L);
        when(routes.findRoutes(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new RouteSummary("501", "501", "Queen", TransitMode.STREETCAR, "DA251D", "FFFFFF", true)));

        mockMvc.perform(get("/api/v1/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].routeId").value("501"))
                .andExpect(jsonPath("$.data.items[0].mode").value("STREETCAR"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                // Enough for a client to paginate without recomputing it.
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                // Schedule data has no feed age, and claiming one would be a lie.
                .andExpect(jsonPath("$.meta.source").value("STATIC"))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.ageSeconds").isEmpty());
    }

    @Test
    void anOverLargePageIsRejectedAsAClientErrorNamingTheParameter() throws Exception {
        mockMvc.perform(get("/api/v1/routes").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.violations[0].parameter").value("size"))
                .andExpect(jsonPath("$.violations[0].message").value(org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    void aNegativePageIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/routes").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("page"));
    }

    @Test
    void anUnknownRouteIsNotFoundRatherThanEmpty() throws Exception {
        when(routes.findRoute("9999")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/routes/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Route"))
                .andExpect(jsonPath("$.resourceId").value("9999"));
    }

    @Test
    void askingForStopsOfAnUnknownRouteIsNotFoundRatherThanAnEmptyList() throws Exception {
        when(routes.exists("9999")).thenReturn(false);

        // An empty list would say the route exists and serves nothing.
        mockMvc.perform(get("/api/v1/routes/9999/stops"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidDirectionIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/routes/501/shape").param("direction", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("direction"));
    }

    @Test
    void simplificationBeyondTheCapIsRejected() throws Exception {
        // Past about 100 m a simplified route stops following the street it runs on.
        mockMvc.perform(get("/api/v1/routes/501/shape").param("simplifyMetres", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("simplifyMetres"));
    }

    @Test
    void nearbyRequiresCoordinatesAndCapsItsRadius() throws Exception {
        mockMvc.perform(get("/api/v1/stops/nearby").param("lon", "-79.4"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/stops/nearby")
                        .param("lat", "43.6").param("lon", "-79.4").param("radiusMetres", "99999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("radiusMetres"));
    }

    @Test
    void impossibleCoordinatesAreRejectedBeforeReachingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/stops/nearby").param("lat", "999").param("lon", "-79.4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("lat"));
    }

    @Test
    void searchRequiresATermAndCapsItsResults() throws Exception {
        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search").param("q", "queen").param("limit", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].parameter").value("limit"));
    }

    @Test
    void anUnknownStopIsNotFound() throws Exception {
        when(stops.findStop(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/stops/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Stop"));
    }
}
