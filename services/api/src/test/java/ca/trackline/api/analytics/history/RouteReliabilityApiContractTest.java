package ca.trackline.api.analytics.history;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import ca.trackline.api.common.error.GlobalExceptionHandler;
import ca.trackline.api.persistence.query.ReliabilityQueryRepository;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static ca.trackline.api.analytics.history.HistoryFixtures.consecutiveHours;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of the reliability endpoint: that figures appear only with enough history behind them,
 * that not-enough is a stated answer with the counts needed to show how far off it is, and that the window
 * is one of a fixed few.
 */
@WebMvcTest(controllers = RouteReliabilityController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class RouteReliabilityApiContractTest {

    private static final Instant NOW = Instant.parse("2026-10-14T14:00:30Z");

    /** Midnight at the start of Tuesday 13 October 2026, Toronto time. */
    private static final Instant TUESDAY = Instant.parse("2026-10-13T04:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReliabilityQueryRepository hourly;

    @MockitoBean
    private RouteQueryRepository routes;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(routes.exists("501")).thenReturn(true);
        when(hourly.findForRoute(eq("501"), any())).thenReturn(List.of());
    }

    @Test
    void aRouteWithNoHistoryIsAStatedAnswerWithNoFiguresAndWhatWouldBeNeeded() throws Exception {
        mockMvc.perform(get("/api/v1/routes/501/reliability"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.data.routeId").value("501"))
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.data.reason").value("NO_SAMPLES"))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.byHour").isEmpty())
                .andExpect(jsonPath("$.data.byWeekday").isEmpty())
                .andExpect(jsonPath("$.data.windowDays").value(7))
                .andExpect(jsonPath("$.data.through").doesNotExist())
                .andExpect(jsonPath("$.data.timeZone").value("America/Toronto"))
                .andExpect(jsonPath("$.data.needs.coveredHours").value(24))
                .andExpect(jsonPath("$.data.needs.samplesPerHour").value(20))
                .andExpect(jsonPath("$.data.needs.judgedSamples").value(240))
                .andExpect(jsonPath("$.data.coverage.hoursCovered").value(0))
                .andExpect(jsonPath("$.meta.source").value("STORED"))
                .andExpect(jsonPath("$.meta.degraded").value(false))
                .andExpect(jsonPath("$.meta.stale").value(false));
    }

    @Test
    void justUnderTheMinimumIsStillInsufficientAndSaysHowFarOff() throws Exception {
        when(hourly.findForRoute(eq("501"), any())).thenReturn(consecutiveHours(TUESDAY, 23, 20, 20, 10, 10));

        mockMvc.perform(get("/api/v1/routes/501/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INSUFFICIENT_HISTORY"))
                .andExpect(jsonPath("$.data.reason").value("TOO_FEW_HOURS"))
                .andExpect(jsonPath("$.data.coverage.hoursCovered").value(23))
                .andExpect(jsonPath("$.data.coverage.samples").value(23 * 60))
                // Nothing is computed below the minimum, not even a rough share.
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.byHour").isEmpty());
    }

    @Test
    void withEnoughHistoryTheFiguresAreReturnedWithTheCountsTheyCameFrom() throws Exception {
        when(hourly.findForRoute(eq("501"), any())).thenReturn(consecutiveHours(TUESDAY, 24, 20, 20, 10, 10));

        mockMvc.perform(get("/api/v1/routes/501/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.reason").doesNotExist())
                .andExpect(jsonPath("$.data.summary.samples").value(1440))
                .andExpect(jsonPath("$.data.summary.judged").value(1440))
                .andExpect(jsonPath("$.data.summary.unjudged").value(0))
                .andExpect(jsonPath("$.data.summary.excellent").value(480))
                .andExpect(jsonPath("$.data.summary.normal").value(480))
                .andExpect(jsonPath("$.data.summary.unstable").value(240))
                .andExpect(jsonPath("$.data.summary.majorDisruption").value(240))
                // 960 of 1,440, to a tenth of a percent.
                .andExpect(jsonPath("$.data.summary.steadyShare").value(0.667))
                .andExpect(jsonPath("$.data.summary.variation").value(0.3))
                .andExpect(jsonPath("$.data.summary.gapRatio").value(1.2))
                .andExpect(jsonPath("$.data.summary.bunchingShare").value(0.0))
                .andExpect(jsonPath("$.data.coverage.hoursCovered").value(24))
                .andExpect(jsonPath("$.data.coverage.days").value(1))
                // The hour of the latest sample ends an hour after it starts.
                .andExpect(jsonPath("$.data.through").value("2026-10-14T04:00:00Z"))
                // One day: the breakdowns are withheld as an anecdote.
                .andExpect(jsonPath("$.data.byHour").isEmpty())
                .andExpect(jsonPath("$.data.byWeekday").isEmpty());
    }

    @Test
    void aBreakdownBarAppearsWithTheDaysItIsDrawnFrom() throws Exception {
        when(hourly.findForRoute(eq("501"), any())).thenReturn(consecutiveHours(TUESDAY, 72, 20, 20, 10, 10));

        mockMvc.perform(get("/api/v1/routes/501/reliability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.byHour.length()").value(24))
                .andExpect(jsonPath("$.data.byHour[0].hour").value(0))
                .andExpect(jsonPath("$.data.byHour[0].days").value(3))
                .andExpect(jsonPath("$.data.byHour[0].figures.samples").value(180));
    }

    @Test
    void theDefaultWindowIsSevenDaysBack() throws Exception {
        mockMvc.perform(get("/api/v1/routes/501/reliability")).andExpect(status().isOk());

        verifyWindowStart(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void eachOfTheOfferedWindowsReadsThatFarBack() throws Exception {
        for (int days : List.of(1, 30, 90)) {
            mockMvc.perform(get("/api/v1/routes/501/reliability").param("window", days + "d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.windowDays").value(days));
        }

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(hourly, times(3)).findForRoute(eq("501"), from.capture());
        assertThat(from.getAllValues()).containsExactly(
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void aWindowThatIsNotOneOfTheOfferedIsABadRequestListingThem() throws Exception {
        for (String window : List.of("14d", "7", "week", "0d", "-7d", "7D")) {
            mockMvc.perform(get("/api/v1/routes/501/reliability").param("window", window))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("[1d, 30d, 7d, 90d]")));
        }
    }

    @Test
    void anUnknownRouteIsNotFoundAndTheSummariesAreNeverQueriedForIt() throws Exception {
        when(routes.exists("nope")).thenReturn(false);

        mockMvc.perform(get("/api/v1/routes/nope/reliability"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(hourly);
    }

    private void verifyWindowStart(Instant expected) {
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(hourly).findForRoute(eq("501"), from.capture());
        assertThat(from.getValue()).isEqualTo(expected);
    }
}
