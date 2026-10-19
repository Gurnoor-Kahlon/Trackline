package ca.trackline.api.analytics.health;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.analytics.health.RouteHealthModels.DirectionHealth;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthMetrics;
import ca.trackline.api.analytics.health.RouteHealthModels.HealthSnapshot;
import ca.trackline.api.analytics.health.RouteHealthModels.RouteHealth;
import ca.trackline.api.analytics.spacing.SpacingBasis;
import ca.trackline.api.analytics.health.RouteHealthStore.HealthRead;
import ca.trackline.api.common.envelope.DataSource;
import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import ca.trackline.api.routes.RouteDtos.RouteSummary;
import ca.trackline.api.routes.TransitMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the route-health endpoints: that a verdict always carries the figures
 * and the age behind it, that "no verdict" is a stated answer and never a missing one, and
 * that the list puts the worst routes first.
 */
@WebMvcTest(controllers = RouteHealthController.class)
@Import(GlobalExceptionHandler.class)
// These are about what each controller returns. Whether an address needs a token is checked once, against
// the real filter chain, in PublicEndpointsStayOpenTest.
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class RouteHealthApiContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T14:00:30Z");
    private static final Instant FEED = Instant.parse("2026-10-12T14:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteHealthStore store;

    @MockitoBean
    private RouteQueryRepository routes;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void fixTheClock() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static RouteSummary summary(String id, String shortName, boolean realtime) {
        return new RouteSummary(
                id, shortName, "Name " + shortName, realtime ? TransitMode.STREETCAR : TransitMode.SUBWAY,
                "DA251D", "FFFFFF", realtime);
    }

    private static HealthMetrics metrics(int vehicles) {
        return new HealthMetrics(
                vehicles, vehicles - 1, 3012.3456, 4231.9876, 0.31234, 1.41234, 3000.0, 6.54321, 0.76543, 600.0,
                5.0123, 1, 2, SpacingBasis.SCHEDULE);
    }

    private static RouteHealth health(String id, RouteHealthState state, HealthConfidence confidence, int vehicles,
            ReasonCode... reasons) {
        DirectionHealth direction = new DirectionHealth(
                (short) 0, state, confidence, List.of(reasons), metrics(vehicles));
        return new RouteHealth(id, state, confidence, List.of(reasons), List.of(direction), 1);
    }

    private void storing(RouteHealth... routes) {
        when(store.read()).thenReturn(new HealthRead(
                new HealthSnapshot(FEED, FEED.plusSeconds(2), true, List.of(routes)), DataSource.LIVE, false));
    }

    // --- one route ------------------------------------------------------------------------------------

    @Test
    void aRoutesVerdictCarriesItsReasonsAndTheFiguresBehindIt() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        storing(health("501", RouteHealthState.UNSTABLE, HealthConfidence.MEDIUM, 5,
                ReasonCode.HIGH_HEADWAY_VARIATION, ReasonCode.LARGE_GAP));

        mockMvc.perform(get("/api/v1/routes/501/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.routeId").value("501"))
                .andExpect(jsonPath("$.data.state").value("UNSTABLE"))
                .andExpect(jsonPath("$.data.confidence").value("MEDIUM"))
                .andExpect(jsonPath("$.data.reasons[0]").value("HIGH_HEADWAY_VARIATION"))
                .andExpect(jsonPath("$.data.reasons[1]").value("LARGE_GAP"))
                .andExpect(jsonPath("$.data.unplacedVehicles").value(1))
                .andExpect(jsonPath("$.data.computedAt").value("2026-10-12T14:00:02Z"))
                .andExpect(jsonPath("$.data.directions[0].directionId").value(0))
                .andExpect(jsonPath("$.data.directions[0].metrics.basis").value("SCHEDULE"))
                .andExpect(jsonPath("$.data.directions[0].metrics.vehicles").value(5));
    }

    @Test
    void figuresAreRoundedForReadingButNotChangedInKind() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        storing(health("501", RouteHealthState.NORMAL, HealthConfidence.HIGH, 5));

        mockMvc.perform(get("/api/v1/routes/501/health"))
                // Distances to a tenth of a metre, ratios to a hundredth.
                .andExpect(jsonPath("$.data.directions[0].metrics.meanGapMetres").value(3012.3))
                .andExpect(jsonPath("$.data.directions[0].metrics.maxGapMetres").value(4232.0))
                .andExpect(jsonPath("$.data.directions[0].metrics.variation").value(0.31))
                .andExpect(jsonPath("$.data.directions[0].metrics.gapRatio").value(1.41))
                .andExpect(jsonPath("$.data.directions[0].metrics.supplyRatio").value(0.77))
                .andExpect(jsonPath("$.data.directions[0].metrics.speedMetresPerSecond").value(5.01));
    }

    @Test
    void theEnvelopeSaysHowOldTheFeedWasAndWhichTierAnswered() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        storing(health("501", RouteHealthState.NORMAL, HealthConfidence.HIGH, 5));

        mockMvc.perform(get("/api/v1/routes/501/health"))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.feedTimestamp").value("2026-10-12T14:00:00Z"))
                .andExpect(jsonPath("$.meta.ageSeconds").value(30))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.degraded").value(false));
    }

    @Test
    void anAnswerFromThisInstancesOwnCopyIsMarkedDegraded() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        when(store.read()).thenReturn(new HealthRead(
                new HealthSnapshot(FEED, FEED, true, List.of(health("501", RouteHealthState.NORMAL, HealthConfidence.HIGH, 5))),
                DataSource.CACHED, true));

        mockMvc.perform(get("/api/v1/routes/501/health"))
                .andExpect(jsonPath("$.meta.source").value("CACHED"))
                .andExpect(jsonPath("$.meta.degraded").value(true));
    }

    @Test
    void aVerdictFromAnOldFeedIsFlaggedStale() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        when(clock.instant()).thenReturn(FEED.plusSeconds(300));
        storing(health("501", RouteHealthState.EXCELLENT, HealthConfidence.HIGH, 5));

        mockMvc.perform(get("/api/v1/routes/501/health"))
                .andExpect(jsonPath("$.meta.stale").value(true))
                .andExpect(jsonPath("$.meta.ageSeconds").value(300));
    }

    @Test
    void aRouteWithNoVehiclesRightNowIsUnknownForWantOfThem() throws Exception {
        when(routes.findRoute("504")).thenReturn(Optional.of(summary("504", "504", true)));
        storing(health("501", RouteHealthState.NORMAL, HealthConfidence.HIGH, 5));

        mockMvc.perform(get("/api/v1/routes/504/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.confidence").value("NONE"))
                .andExpect(jsonPath("$.data.reasons[0]").value("INSUFFICIENT_VEHICLES"))
                .andExpect(jsonPath("$.data.directions").isEmpty());
    }

    @Test
    void aSubwayLineSaysTheFeedPublishesNoPositionsForIt() throws Exception {
        when(routes.findRoute("1")).thenReturn(Optional.of(summary("1", "Line 1", false)));
        storing(health("501", RouteHealthState.NORMAL, HealthConfidence.HIGH, 5));

        mockMvc.perform(get("/api/v1/routes/1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.reasons[0]").value("NO_REALTIME_FEED"));
    }

    @Test
    void beforeAnyAnalysisTheAnswerIsUnknownAndNoDataNotAnEmptyHealthyNetwork() throws Exception {
        when(routes.findRoute("501")).thenReturn(Optional.of(summary("501", "501", true)));
        when(store.read()).thenReturn(HealthRead.nothing());

        mockMvc.perform(get("/api/v1/routes/501/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.reasons[0]").value("NOT_ANALYSED_YET"))
                .andExpect(jsonPath("$.data.computedAt").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.feedTimestamp").isEmpty());
    }

    @Test
    void anUnknownRouteIsANotFoundProblem() throws Exception {
        when(routes.findRoute(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/routes/no-such-route/health"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // --- the list -------------------------------------------------------------------------------------------

    private void naming(RouteSummary... named) {
        when(routes.findRoutesByIds(any())).thenReturn(List.of(named));
    }

    @Test
    void theListPutsTheWorstRoutesFirstAndNoVerdictLast() throws Exception {
        storing(
                health("1", RouteHealthState.NORMAL, HealthConfidence.MEDIUM, 4),
                health("2", RouteHealthState.UNKNOWN, HealthConfidence.NONE, 1, ReasonCode.INSUFFICIENT_VEHICLES),
                health("3", RouteHealthState.MAJOR_DISRUPTION, HealthConfidence.HIGH, 6, ReasonCode.LARGE_GAP),
                health("4", RouteHealthState.UNSTABLE, HealthConfidence.MEDIUM, 4),
                health("5", RouteHealthState.EXCELLENT, HealthConfidence.HIGH, 8));
        naming(summary("1", "1", true), summary("2", "2", true), summary("3", "3", true),
                summary("4", "4", true), summary("5", "5", true));

        mockMvc.perform(get("/api/v1/health/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routes[*].routeId", contains("3", "4", "1", "5", "2")))
                .andExpect(jsonPath("$.data.routes[0].shortName").value("3"))
                .andExpect(jsonPath("$.data.routes[0].reasons[0]").value("LARGE_GAP"))
                .andExpect(jsonPath("$.data.routes[0].vehicles").value(6))
                .andExpect(jsonPath("$.meta.source").value("LIVE"));
    }

    @Test
    void withinAStateTheRouteJudgedOnMoreVehiclesComesFirst() throws Exception {
        storing(
                health("small", RouteHealthState.UNSTABLE, HealthConfidence.LOW, 3),
                health("large", RouteHealthState.UNSTABLE, HealthConfidence.HIGH, 9));
        naming(summary("small", "1", true), summary("large", "2", true));

        mockMvc.perform(get("/api/v1/health/routes"))
                .andExpect(jsonPath("$.data.routes[*].routeId", contains("large", "small")));
    }

    @Test
    void theListCanBeNarrowedToTheStatesAskedFor() throws Exception {
        storing(
                health("1", RouteHealthState.NORMAL, HealthConfidence.MEDIUM, 4),
                health("3", RouteHealthState.MAJOR_DISRUPTION, HealthConfidence.HIGH, 6),
                health("4", RouteHealthState.UNSTABLE, HealthConfidence.MEDIUM, 4));
        naming(summary("3", "3", true), summary("4", "4", true));

        mockMvc.perform(get("/api/v1/health/routes?state=UNSTABLE&state=MAJOR_DISRUPTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routes.length()").value(2));
    }

    @Test
    void aRouteTheScheduleNoLongerHasIsLeftOutBecauseItCannotBeNamed() throws Exception {
        storing(
                health("1", RouteHealthState.NORMAL, HealthConfidence.MEDIUM, 4),
                health("gone", RouteHealthState.UNSTABLE, HealthConfidence.MEDIUM, 4));
        naming(summary("1", "1", true));

        mockMvc.perform(get("/api/v1/health/routes"))
                .andExpect(jsonPath("$.data.routes.length()").value(1))
                .andExpect(jsonPath("$.data.routes[0].routeId").value("1"));
    }

    @Test
    void anUnknownStateIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/health/routes?state=TERRIBLE"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void beforeAnyAnalysisTheListIsEmptyWithNoDataProvenance() throws Exception {
        when(store.read()).thenReturn(HealthRead.nothing());

        mockMvc.perform(get("/api/v1/health/routes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routes").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"));
    }

    @Test
    void theStateFilterIsOptional() throws Exception {
        storing(health("1", RouteHealthState.NORMAL, HealthConfidence.MEDIUM, 4));
        naming(summary("1", "1", true));

        mockMvc.perform(get("/api/v1/health/routes"))
                .andExpect(jsonPath("$.data.routes.length()").value(1));
    }
}
