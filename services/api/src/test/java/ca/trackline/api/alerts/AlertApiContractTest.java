package ca.trackline.api.alerts;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import ca.trackline.api.alerts.AlertDtos.AffectedRoute;
import ca.trackline.api.alerts.AlertDtos.AffectedStop;
import ca.trackline.api.alerts.AlertDtos.AlertStatus;
import ca.trackline.api.alerts.AlertDtos.AlertView;
import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.persistence.query.AlertQueryRepository;
import ca.trackline.api.routes.TransitMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the alert endpoints: what a client is told about the alerts and
 * about how current they are, and that a bad request is a client error.
 */
@WebMvcTest(controllers = AlertController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AlertApiContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AlertQueryRepository alerts;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void fixTheClock() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static AlertView view(long id) {
        return new AlertView(
                id, "src-" + id, AlertStatus.ACTIVE, AlertCause.UNKNOWN_CAUSE, AlertEffect.UNKNOWN_EFFECT,
                "506 Carlton: Detour via Bay St,", "506 Carlton: Detour via Bay St, Dundas St E due to construction.",
                null, NOW.minusSeconds(3600), NOW.minusSeconds(30), NOW.minusSeconds(3600), null,
                List.of(new AffectedRoute("506", true, "506", "Carlton", TransitMode.STREETCAR, "DA251D", "FFFFFF"),
                        new AffectedRoute("ZZ", false, null, null, null, null, null)),
                List.of(new AffectedStop("3718", true, "Union Station")));
    }

    @Test
    void listsAlertsWithTheirObservedLifecycleAndWhatTheyName() throws Exception {
        when(alerts.count(true, null, null)).thenReturn(1L);
        when(alerts.find(true, null, null, 0, 50)).thenReturn(List.of(view(5)));
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW.minusSeconds(20)));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(5))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                // As published, not interpreted: the TTC states neither.
                .andExpect(jsonPath("$.data.items[0].cause").value("UNKNOWN_CAUSE"))
                .andExpect(jsonPath("$.data.items[0].effect").value("UNKNOWN_EFFECT"))
                .andExpect(jsonPath("$.data.items[0].firstSeenAt").value("2026-10-12T11:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].resolvedAt").isEmpty())
                .andExpect(jsonPath("$.data.items[0].routes[0].known").value(true))
                .andExpect(jsonPath("$.data.items[0].routes[0].mode").value("STREETCAR"))
                .andExpect(jsonPath("$.data.items[0].routes[1].known").value(false))
                .andExpect(jsonPath("$.data.items[0].routes[1].shortName").isEmpty())
                .andExpect(jsonPath("$.data.items[0].stops[0].name").value("Union Station"));
    }

    @Test
    void saysWhereTheDataCameFromAndHowOldTheFeedIs() throws Exception {
        when(alerts.count(true, null, null)).thenReturn(0L);
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW.minusSeconds(20)));

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(jsonPath("$.meta.source").value("STORED"))
                .andExpect(jsonPath("$.meta.feedTimestamp").value("2026-10-12T11:59:40Z"))
                .andExpect(jsonPath("$.meta.ageSeconds").value(20))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.degraded").value(false));
    }

    @Test
    void anAlertsFeedIsCalledStaleOnlyAfterFiveMinutes() throws Exception {
        when(alerts.count(true, null, null)).thenReturn(0L);

        // Ninety seconds is ordinary for a feed polled once a minute.
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW.minusSeconds(90)));
        mockMvc.perform(get("/api/v1/alerts")).andExpect(jsonPath("$.meta.stale").value(false));

        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW.minusSeconds(301)));
        mockMvc.perform(get("/api/v1/alerts")).andExpect(jsonPath("$.meta.stale").value(true));
    }

    @Test
    void anEmptyListBeforeAnyIngestionIsNoDataNotNoAlerts() throws Exception {
        when(alerts.count(true, null, null)).thenReturn(0L);
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.meta.source").value("NO_DATA"))
                .andExpect(jsonPath("$.meta.feedTimestamp").isEmpty());
    }

    @Test
    void doesNotQueryForRowsWhenTheCountIsZero() throws Exception {
        when(alerts.count(true, null, null)).thenReturn(0L);
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isOk());

        verify(alerts, never()).find(anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void passesTheFiltersThrough() throws Exception {
        when(alerts.count(false, "501", "3718")).thenReturn(1L);
        when(alerts.find(false, "501", "3718", 2, 10)).thenReturn(List.of(view(1)));
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW));

        mockMvc.perform(get("/api/v1/alerts?active=false&route=501&stop=3718&page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10));

        verify(alerts).find(eq(false), eq("501"), eq("3718"), eq(2), eq(10));
    }

    @Test
    void rejectsAPageSizeBeyondTheCap() throws Exception {
        mockMvc.perform(get("/api/v1/alerts?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void rejectsANegativePageAndAZeroSize() throws Exception {
        mockMvc.perform(get("/api/v1/alerts?page=-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/alerts?size=0")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsARouteOrStopThatCouldNotBeAnIdentifier() throws Exception {
        mockMvc.perform(get("/api/v1/alerts?route=501;DROP TABLE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/alerts?stop=" + "x".repeat(33))).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAFlagThatIsNotABoolean() throws Exception {
        mockMvc.perform(get("/api/v1/alerts?active=maybe")).andExpect(status().isBadRequest());
    }

    @Test
    void returnsOneAlertById() throws Exception {
        when(alerts.findById(5)).thenReturn(Optional.of(view(5)));
        when(alerts.lastAppliedFeedTimestamp()).thenReturn(Optional.of(NOW));

        mockMvc.perform(get("/api/v1/alerts/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.sourceAlertId").value("src-5"))
                .andExpect(jsonPath("$.meta.source").value("STORED"));
    }

    @Test
    void anUnknownAlertIsANotFoundProblem() throws Exception {
        when(alerts.findById(99)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/alerts/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void anIdThatIsNotANumberIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/abc")).andExpect(status().isBadRequest());
    }
}
