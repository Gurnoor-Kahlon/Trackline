package ca.trackline.api.gtfs.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Runs the import from the command line and exits.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.arguments=--import-gtfs
 * </pre>
 *
 * <p>This is the primary way to load the feed: it is an occasional operator task,
 * not something the application should do on startup. Importing automatically at
 * boot would re-download on every restart and make startup depend on a third
 * party being reachable.
 */
@Component
public class GtfsImportRunner implements ApplicationRunner {

    /** Presence of this argument triggers the import. */
    public static final String IMPORT_ARGUMENT = "import-gtfs";

    private static final Logger log = LoggerFactory.getLogger(GtfsImportRunner.class);

    private final GtfsImportService importService;
    private final ApplicationContext context;

    public GtfsImportRunner(GtfsImportService importService, ApplicationContext context) {
        this.importService = importService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(IMPORT_ARGUMENT)) {
            return;
        }

        GtfsImportResult result = importService.importLatest();
        log.info(
                "GTFS import finished status={} rows={} window={}..{} durationMs={}",
                result.status(),
                result.rowCounts(),
                result.feedStartDate(),
                result.feedEndDate(),
                result.durationMs());

        if (!result.succeeded()) {
            log.error("GTFS import failed: {}", result.failureReason());
        }

        // A command-line import is a one-shot task: exit rather than leaving a web
        // server running, and report success through the exit code so a script can
        // act on it.
        int exitCode = result.succeeded() ? 0 : 1;
        System.exit(SpringApplication.exit(context, (ExitCodeGenerator) () -> exitCode));
    }
}
