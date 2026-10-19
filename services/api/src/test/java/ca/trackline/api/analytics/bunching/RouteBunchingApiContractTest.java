package ca.trackline.api.analytics.bunching;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import ca.trackline.api.analytics.bunching.BunchingDtos.BunchingEventView;
import ca.trackline.api.analytics.bunching.BunchingDtos.EventStatus;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingCandidate;
import ca.trackline.api.analytics.bunching.BunchingModels.BunchingSeverity;
import ca.trackline.api.analytics.bunching.BunchingModels.DirectionSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.PlacedVehicle;
import ca.trackline.api.analytics.bunching.BunchingModels.RouteSpacing;
import ca.trackline.api.analytics.bunching.BunchingModels.SpacingSnapshot;
import ca.trackline.api.analytics.bunching.BunchingModels.VehicleRole;
import ca.trackline.api.analytics.health.RouteHealthStore;
import ca.trackline.api.analytics.health.RouteHealthStore.SpacingRead;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.persistence.query.BunchingQueryRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the bunching endpoint: that it says where vehicles are and which are in a pack,
 * that an absent analysis is a stated answer and not a healthy one, and that the history is bounded.
 */
@WebMvcTest(controllers = RouteBunchingController.class)
@Import(GlobalExceptionHandler.class)
// These are about what each controller returns. Whether an address needs a token is checked once, against
// the real filter chain, in PublicEndpointsStayOpenTest.
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class RouteBunchingApiContractTest {

    private static final Instant NOW = Instant.parse("2026-10-13T14:00:30Z");
    private static final Instant FEED = Instant.parse("2026-10-13T14:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteHealthStore store;

    @MockitoBean
    private BunchingQueryRepository events;

    @MockitoBean
    private RouteQueryRepository routes;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routes.exists("501")).thenReturn(true);
        when(events.findForRoute(anyString(), any(), anyInt())).thenReturn(List.of());
    }

    private static DirectionSpacing direction() {
        BunchingCandidate bunch = new BunchingCandidate(
                "501", (short) 0, List.of("a", "b"), 412.34, 3000.04, 6512.66, BunchingSeverity.MODERATE, SpacingBasis.SCHEDULE);
        return new DirectionSpacing(
                (short) 0, 17_400.55, false, 3000.04, SpacingBasis.SCHEDULE,
                List.of(
                        new PlacedVehicle("start", 80.0, VehicleRole.AT_TERMINAL),
                        new PlacedVehicle("a", 1000.04, VehicleRole.BUNCHED),
                        new PlacedVehicle("b", 1412.38, VehicleRole.BUNCHED),
                        new PlacedVehicle("c", 8000.0, VehicleRole.SPACED),
                        new PlacedVehicle("old", 9000.0, VehicleRole.STALE)),
                List.of(bunch));
    }

    private void spacing(DataSource source, boolean degraded, RouteSpacing... routesSpacing) {
        when(store.readSpacing()).thenReturn(new SpacingRead(
                new SpacingSnapshot(FEED, FEED.plusSeconds(2), List.of(routesSpacing)), source, degraded));
    }

    private static BunchingEventView event(long id, EventStatus status) {
        return new BunchingEventView(
                id, (short) 0, status, List.of("a", "b"), 3, 400.0, 3000.0, 6500.0, SpacingBasis.SCHEDULE,
                BunchingSeverity.MODERATE, BunchingSeverity.SEVERE, 12,
                Instant.parse("2026-10-13T13:50:00Z"), Instant.parse("2026-10-13T14:00:00Z"),
                status == EventStatus.RESOLVED ? Instant.parse("2026-10-13T14:01:00Z") : null);
    }

    @Test
    void saysWhereEveryVehicleIsAndWhichAreBunched() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("501", List.of(direction())));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.routeId").value("501"))
                .andExpect(jsonPath("$.data.directions[0].directionId").value(0))
                .andExpect(jsonPath("$.data.directions[0].loop").value(false))
                .andExpect(jsonPath("$.data.directions[0].basis").value("SCHEDULE"))
                .andExpect(jsonPath("$.data.directions[0].vehicles.length()").value(5))
                .andExpect(jsonPath("$.data.directions[0].vehicles[0].role").value("AT_TERMINAL"))
                .andExpect(jsonPath("$.data.directions[0].vehicles[1].role").value("BUNCHED"))
                .andExpect(jsonPath("$.data.directions[0].vehicles[4].role").value("STALE"))
                .andExpect(jsonPath("$.data.directions[0].bunches[0].vehicleIds[0]").value("a"))
                .andExpect(jsonPath("$.data.directions[0].bunches[0].severity").value("MODERATE"))
                .andExpect(jsonPath("$.data.computedAt").value("2026-10-13T14:00:02Z"));
    }

    @Test
    void distancesAreRoundedToATenthOfAMetre() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("501", List.of(direction())));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(jsonPath("$.data.directions[0].lengthMetres").value(17_400.6))
                .andExpect(jsonPath("$.data.directions[0].expectedGapMetres").value(3000.0))
                .andExpect(jsonPath("$.data.directions[0].vehicles[2].progressMetres").value(1412.4))
                .andExpect(jsonPath("$.data.directions[0].bunches[0].spanMetres").value(412.3))
                .andExpect(jsonPath("$.data.directions[0].bunches[0].serviceGapMetres").value(6512.7));
    }

    @Test
    void theEnvelopeSaysHowOldTheVehicleDataIsAndWhoAnswered() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("501", List.of(direction())));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.feedTimestamp").value("2026-10-13T14:00:00Z"))
                .andExpect(jsonPath("$.meta.ageSeconds").value(30))
                .andExpect(jsonPath("$.meta.degraded").value(false));
    }

    @Test
    void anAnswerFromThisInstancesOwnCopyIsMarkedDegraded() throws Exception {
        spacing(DataSource.CACHED, true, new RouteSpacing("501", List.of(direction())));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(jsonPath("$.meta.source").value("CACHED"))
                .andExpect(jsonPath("$.meta.degraded").value(true));
    }

    @Test
    void aRouteWithNoVehiclesRightNowHasNoDirectionsButStillListsItsRecentEvents() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("504", List.of(direction())));
        when(events.findForRoute(eq("501"), any(), anyInt())).thenReturn(List.of(event(9, EventStatus.RESOLVED)));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directions").isEmpty())
                .andExpect(jsonPath("$.data.events.length()").value(1))
                .andExpect(jsonPath("$.data.events[0].id").value(9));
    }

    @Test
    void beforeAnyAnalysisTheAnswerIsNoDataNotAHealthyRoute() throws Exception {
        when(store.readSpacing()).thenReturn(SpacingRead.nothing());

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.directions").isEmpty())
                .andExpect(jsonPath("$.data.computedAt").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.feedTimestamp").isEmpty());
    }

    @Test
    void eventsCarryTheirLifecycleAsTimesTheyWereSeenAt() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("501", List.of(direction())));
        when(events.findForRoute(eq("501"), any(), anyInt()))
                .thenReturn(List.of(event(2, EventStatus.ACTIVE), event(1, EventStatus.RESOLVED)));

        mockMvc.perform(get("/api/v1/routes/501/bunching"))
                .andExpect(jsonPath("$.data.events[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.events[0].resolvedAt").isEmpty())
                .andExpect(jsonPath("$.data.events[0].peakVehicles").value(3))
                .andExpect(jsonPath("$.data.events[0].peakSeverity").value("SEVERE"))
                .andExpect(jsonPath("$.data.events[0].sightings").value(12))
                .andExpect(jsonPath("$.data.events[0].firstSeenAt").value("2026-10-13T13:50:00Z"))
                .andExpect(jsonPath("$.data.events[1].status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.events[1].resolvedAt").value("2026-10-13T14:01:00Z"));
    }

    @Test
    void historyIsBoundedToTheLastDayAndATwentyFiveEvents() throws Exception {
        spacing(DataSource.LIVE, false, new RouteSpacing("501", List.of(direction())));

        mockMvc.perform(get("/api/v1/routes/501/bunching")).andExpect(status().isOk());

        verify(events).findForRoute("501", NOW.minus(RouteBunchingController.RECENT), RouteBunchingController.MAX_EVENTS);
    }

    @Test
    void anUnknownRouteIsANotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/v1/routes/no-such-route/bunching"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
