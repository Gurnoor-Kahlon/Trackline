package ca.trackline.api.gtfs.schedule.derive;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Everything derived from the imported feed, run as part of the import.
 *
 * <p>Inside the import's transaction on purpose: an active network without its
 * geometry is not servable — vehicles could not be placed on a route and no map
 * could be drawn — so the two must become visible together or not at all.
 */
@Component
public class NetworkDerivation {

    private static final Logger log = LoggerFactory.getLogger(NetworkDerivation.class);

    private final RouteShapeBuilder routeShapeBuilder;
    private final StationClusterer stationClusterer;
    private final ScheduledHeadwayBuilder headwayBuilder;

    public NetworkDerivation(
            RouteShapeBuilder routeShapeBuilder,
            StationClusterer stationClusterer,
            ScheduledHeadwayBuilder headwayBuilder) {
        this.routeShapeBuilder = routeShapeBuilder;
        this.stationClusterer = stationClusterer;
        this.headwayBuilder = headwayBuilder;
    }

    public DerivationSummary derive(Connection connection, long feedVersionId, LocalDate feedStart, LocalDate feedEnd)
            throws SQLException {
        long started = System.nanoTime();

        RouteShapeBuilder.BuildSummary shapes = routeShapeBuilder.build(connection, feedVersionId);
        int stations = stationClusterer.cluster(connection);
        int headways = headwayBuilder.build(connection, feedVersionId, feedStart, feedEnd);

        long durationMs = (System.nanoTime() - started) / 1_000_000;
        log.info(
                "Derived network geometry in {} ms: shapes={} primary={} loops={} stations={} headways={}",
                durationMs, shapes.shapes(), shapes.primary(), shapes.loops(), stations, headways);

        return new DerivationSummary(
                shapes.shapes(), shapes.primary(), shapes.loops(), stations, headways, durationMs);
    }

    /**
     * @param shapes         route shapes built
     * @param primaryShapes  one per route and direction, the shape vehicles project onto
     * @param loopShapes     shapes whose endpoints meet
     * @param stations       stations derived from platform grouping
     * @param headwayEntries scheduled headway rows written
     * @param durationMs     wall-clock time for the whole derivation
     */
    public record DerivationSummary(
            int shapes, int primaryShapes, int loopShapes, int stations, int headwayEntries, long durationMs) {
    }
}
