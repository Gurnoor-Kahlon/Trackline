package ca.trackline.api.gtfs.schedule;

import ca.trackline.api.common.envelope.ApiEnvelope;
import ca.trackline.api.common.envelope.ResponseMeta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for the GTFS import.
 *
 * <p>Registered only when {@code trackline.gtfs.static.admin-endpoint} is true, so
 * production does not expose an unauthenticated way to re-download 36 MB and
 * rewrite the network. Real authorization arrives with authentication; until then
 * the endpoint simply does not exist where it should not.
 *
 * <p>The call is synchronous and takes as long as the import does. That is
 * deliberate for an operator-run action — the command-line runner is the primary
 * path, and a job queue for one manual task would be infrastructure without a
 * purpose.
 */
@RestController
@RequestMapping("/api/v1/admin/gtfs")
@ConditionalOnProperty(prefix = "trackline.gtfs.static", name = "admin-endpoint", havingValue = "true")
@Tag(name = "Admin", description = "Operator actions")
public class GtfsImportController {

    private final GtfsImportService importService;
    private final Clock clock;

    public GtfsImportController(GtfsImportService importService, Clock clock) {
        this.importService = importService;
        this.clock = clock;
    }

    @PostMapping("/import")
    @Operation(
            summary = "Import the current GTFS static feed",
            description = "Resolves the archive through the open-data catalogue, downloads it, and "
                    + "replaces the network in one transaction. Returns UNCHANGED without reloading "
                    + "when the published archive matches the active version.")
    public ResponseEntity<ApiEnvelope<GtfsImportResult>> importFeed() {
        if (importService.isRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        GtfsImportResult result = importService.importLatest();
        Instant now = Instant.now(clock);
        HttpStatus status = result.succeeded() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY;

        return ResponseEntity.status(status)
                .body(ApiEnvelope.of(result, ResponseMeta.forStatic(now)));
    }
}
