package ca.trackline.api.stream;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import ca.trackline.api.common.error.InvalidRequestException;
import ca.trackline.api.common.error.ResourceNotFoundException;
import ca.trackline.api.common.geo.BoundingBox;
import ca.trackline.api.persistence.query.RouteQueryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live streams over server-sent events.
 *
 * <p>SSE rather than WebSockets because nothing here needs the browser to speak: the
 * server pushes, the browser listens, and {@code EventSource} brings reconnection and
 * resuming (the Last-Event-ID header) with it for free.
 */
@RestController
@RequestMapping("/api/v1/stream")
@Validated
@Tag(name = "Streams", description = "Live updates over server-sent events")
public class StreamController {

    /** More routes than this is a request for the whole fleet; ask for the whole fleet. */
    private static final int MAX_ROUTES = 20;

    /** GTFS route ids are text, so this is a shape check, not an assumption they are numeric. */
    private static final Pattern ROUTE_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");

    private final StreamHub hub;
    private final RouteQueryRepository routes;

    public StreamController(StreamHub hub, RouteQueryRepository routes) {
        this.hub = hub;
        this.routes = routes;
    }

    @GetMapping(path = "/vehicles", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Live vehicles, as a snapshot followed by changes",
            description = "The first event is `snapshot`, the matching fleet at that moment, named by "
                    + "its fields. Each later `vehicles` event carries only the vehicles that changed "
                    + "and the ids of those that stopped reporting. `heartbeat` events keep the "
                    + "connection alive and carry the feed's age. Send Last-Event-ID (or "
                    + "`lastEventId`) to resume without a new snapshot; if the server cannot prove "
                    + "the missed events are all still available it sends a snapshot instead.")
    public SseEmitter vehicles(
            @RequestParam(required = false) @Size(max = 400) String routes,
            @RequestParam(required = false)
                    @jakarta.validation.constraints.Pattern(
                            regexp = BoundingBox.FORMAT, message = "must be minLon,minLat,maxLon,maxLat")
                    String bbox,
            @RequestParam(required = false) @Size(max = 24) String lastEventId,
            @RequestHeader(name = "Last-Event-ID", required = false) @Size(max = 24) String lastEventIdHeader,
            HttpServletRequest request,
            HttpServletResponse response) {

        StreamFilter filter = new StreamFilter(parseRoutes(routes), bbox == null ? null : BoundingBox.parse(bbox));
        prepare(response);

        // The header is what a browser sends on its own reconnect; the parameter is
        // for a client that opens a fresh connection itself and cannot set headers.
        String resumeFrom = lastEventIdHeader != null ? lastEventIdHeader : lastEventId;
        return hub.connectVehicles(filter, resumeFrom, request.getRemoteAddr());
    }

    @GetMapping(path = "/network", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Network-wide counts, refreshed every cycle",
            description = "Each `network` event is the whole current status, so there is nothing to "
                    + "resume: a missed event is corrected by the next. `activeAlerts` is null until "
                    + "alerts have been ingested once, which is not the same as zero.")
    public SseEmitter network(HttpServletRequest request, HttpServletResponse response) {
        prepare(response);
        return hub.connectNetwork(request.getRemoteAddr());
    }

    /**
     * Headers that keep intermediaries from spoiling a stream: no caching, and no
     * buffering by nginx-style proxies, which would hold events back until a buffer
     * filled and make a live stream arrive in lumps.
     */
    private static void prepare(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
    }

    private Set<String> parseRoutes(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .forEach(ids::add);

        if (ids.size() > MAX_ROUTES) {
            throw new InvalidRequestException(
                    "routes may name at most %d routes; omit it to follow the whole fleet".formatted(MAX_ROUTES));
        }
        for (String id : ids) {
            if (!ROUTE_ID.matcher(id).matches()) {
                throw new InvalidRequestException("'%s' is not a valid route id".formatted(id));
            }
            if (!routes.exists(id)) {
                throw new ResourceNotFoundException("Route", id);
            }
        }
        return ids;
    }
}
