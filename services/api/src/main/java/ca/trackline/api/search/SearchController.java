package ca.trackline.api.search;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import ca.trackline.api.persistence.query.SearchQueryRepository;
import ca.trackline.api.search.SearchDtos.SearchHit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One search endpoint across routes, stops and stations.
 *
 * <p>A single endpoint rather than three: a rider typing into one box does not
 * know or care whether "Kennedy" is a station, a stop or part of a route name, and
 * making the client fan out and merge would push the ranking decision somewhere it
 * cannot see the scores.
 */
@RestController
@RequestMapping("/api/v1/search")
@Validated
@Tag(name = "Search", description = "Routes, stops and stations by name or number")
public class SearchController {

    private static final int MAX_RESULTS = 50;
    private static final int DEFAULT_RESULTS = 20;

    private final SearchQueryRepository search;
    private final Clock clock;

    public SearchController(SearchQueryRepository search, Clock clock) {
        this.search = search;
        this.clock = clock;
    }

    @GetMapping
    @Operation(
            summary = "Search routes, stops and stations",
            description = "Trigram matching, so a near miss still finds its target. Results carry a "
                    + "score so a client can judge how confident a match is.")
    public ApiEnvelope<List<SearchHit>> search(
            @RequestParam @NotBlank @Size(min = 1, max = 100) String q,
            @RequestParam(defaultValue = "" + DEFAULT_RESULTS) @Min(1) @Max(MAX_RESULTS) int limit) {

        return ApiEnvelope.of(search.search(q.trim(), limit), ResponseMeta.forStatic(Instant.now(clock)));
    }
}
