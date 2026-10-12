package ca.trackline.api.common.error;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the shared error contract against a test-only controller, so the
 * contract is covered without adding endpoints to production code purely to
 * make failures reachable.
 */
class GlobalExceptionHandlerTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-27T18:00:00Z");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    @Test
    void missingResourceProducesProblemDetailNamingTheResource() throws Exception {
        mockMvc.perform(get("/test/routes/501"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/resource-not-found"))
                .andExpect(jsonPath("$.detail").value("Route '501' was not found"))
                .andExpect(jsonPath("$.resourceType").value("Route"))
                .andExpect(jsonPath("$.resourceId").value("501"))
                .andExpect(jsonPath("$.instance").value("/test/routes/501"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-27T18:00:00Z"));
    }

    @Test
    void invalidBodyReportsEveryOffendingField() throws Exception {
        mockMvc.perform(post("/test/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"vehicleCount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/validation-failed"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder("name", "vehicleCount")))
                .andExpect(jsonPath("$.timestamp").value("2026-09-27T18:00:00Z"));
    }

    @Test
    void missingRequiredParameterGetsTheSameEnrichedShape() throws Exception {
        mockMvc.perform(get("/test/routes"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // Spring's own exceptions must not be the one family missing these.
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/bad-request"))
                .andExpect(jsonPath("$.instance").value("/test/routes"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-27T18:00:00Z"));
    }

    @Test
    void unexpectedFailureIsGenericAndCarriesATraceableErrorId() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                // No internals: the cause is in the logs against the errorId only.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("IllegalStateException"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ca.trackline"))));
    }

    @Test
    void aStreamClientThatLeftIsDroppedQuietlyNotReportedAsAnUnhandledError() throws Exception {
        mockMvc.perform(get("/test/stream-gone").accept("text/event-stream"))
                // Handled, with nothing written: there is nobody to tell.
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void anIoErrorAnywhereElseIsStillAServerFault() throws Exception {
        mockMvc.perform(get("/test/disk"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.errorId").exists())
                // Nothing about the failure is described to the caller.
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @RestController
    static class FailingController {

        @GetMapping("/test/routes/{id}")
        String missing(@PathVariable String id) {
            throw new ResourceNotFoundException("Route", id);
        }

        @GetMapping("/test/routes")
        String requiresParameter(@RequestParam int limit) {
            return "limit=" + limit;
        }

        @PostMapping("/test/routes")
        String validatesBody(@Valid @RequestBody RouteRequest request) {
            return request.name();
        }

        /** What a client closing the tab mid-connect looks like: the content type is already set. */
        @GetMapping(value = "/test/stream-gone", produces = "text/event-stream")
        String streamClientGone(HttpServletResponse response) throws IOException {
            response.setContentType("text/event-stream");
            throw new IOException("An established connection was aborted by the software in your host machine");
        }

        @GetMapping("/test/disk")
        String diskFailure() throws IOException {
            throw new IOException("disk on fire");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("internal detail that must not be exposed");
        }
    }

    record RouteRequest(@NotBlank String name, @Min(1) int vehicleCount) {
    }
}
