package ca.trackline.api.stream;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import ca.trackline.api.common.geo.BoundingBox;
import ca.trackline.api.stream.StreamEvent.Change;
import ca.trackline.api.stream.StreamEvent.Gone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static ca.trackline.api.stream.StreamTestData.LAT;
import static ca.trackline.api.stream.StreamTestData.LON;
import static ca.trackline.api.stream.StreamTestData.tracked;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one client is sent, and above all that its filter works at both edges.
 */
class VehicleRendererTest {

    private static final Instant NOW = Instant.parse("2026-10-07T18:00:30Z");
    private static final Instant FEED = Instant.parse("2026-10-07T18:00:10Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static VehicleRenderer renderer(StreamFilter filter) {
        return new VehicleRenderer(filter, CLOCK);
    }

    private static StreamFilter routes(String... ids) {
        return new StreamFilter(Set.of(ids), null);
    }

    private static JsonNode body(Outbound outbound) throws Exception {
        return JSON.readTree(outbound.data());
    }

    private static StreamEvent delta(List<Change> changed, List<Gone> removed) {
        return new StreamEvent(
                7, 6, StreamEvent.Kind.DELTA, FEED, 10, 2, List.of(), changed, removed);
    }

    // --- the wire shape -------------------------------------------------------------------

    @Test
    void aSnapshotNamesItsFieldsAndCarriesEachVehicleAsAnArrayInThatOrder() throws Exception {
        Tracked vehicle = new Tracked("4501", "501", 43.6357731, -79.4732361, 217.4f, 7.66f, (short) 2, 1_790_000_000L);

        Outbound outbound = renderer(StreamFilter.none()).snapshot(7, FEED, List.of(vehicle));

        assertThat(outbound.name()).isEqualTo("snapshot");
        assertThat(outbound.id()).isEqualTo("7");
        JsonNode body = body(outbound);
        assertThat(body.get("fields")).extracting(JsonNode::asText)
                .containsExactly("id", "route", "lat", "lon", "bearing", "speed", "occupancy", "timestamp");

        JsonNode row = body.get("vehicles").get(0);
        assertThat(row.get(0).asText()).isEqualTo("4501");
        assertThat(row.get(1).asText()).isEqualTo("501");
        // Rounded to six decimals, about ten centimetres: finer than the GPS behind it.
        assertThat(row.get(2).asDouble()).isEqualTo(43.635773);
        assertThat(row.get(3).asDouble()).isEqualTo(-79.473236);
        assertThat(row.get(4).asInt()).isEqualTo(217);
        assertThat(row.get(5).asDouble()).isEqualTo(7.7);
        assertThat(row.get(6).asInt()).isEqualTo(2);
        assertThat(row.get(7).asLong()).isEqualTo(1_790_000_000L);
    }

    @Test
    void absentFieldsAreNullNotZero() throws Exception {
        Tracked bare = new Tracked("v", null, LAT, LON, null, null, null, 1L);

        JsonNode row = body(renderer(StreamFilter.none()).snapshot(1, FEED, List.of(bare))).get("vehicles").get(0);

        // A vehicle with no route, heading or speed says so; it is not at heading 0.
        assertThat(row.get(1).isNull()).isTrue();
        assertThat(row.get(4).isNull()).isTrue();
        assertThat(row.get(5).isNull()).isTrue();
        assertThat(row.get(6).isNull()).isTrue();
    }

    @Test
    void everyEventStatesHowOldTheFeedIsFromTheServersClock() throws Exception {
        JsonNode body = body(renderer(StreamFilter.none()).snapshot(1, FEED, List.of()));

        // The client works out its own picture's age from these two, never from its own
        // clock, so a laptop set to the wrong time still reports the right age.
        assertThat(body.get("serverTime").asText()).isEqualTo("2026-10-07T18:00:30Z");
        assertThat(body.get("feed").get("timestamp").asText()).isEqualTo("2026-10-07T18:00:10Z");
        assertThat(body.get("feed").get("ageSeconds").asLong()).isEqualTo(20);
    }

    @Test
    void anUnknownFeedTimeIsNullNotAnInventedAge() throws Exception {
        JsonNode body = body(renderer(StreamFilter.none()).snapshot(0, null, List.of()));

        assertThat(body.get("feed").get("timestamp").isNull()).isTrue();
        assertThat(body.get("feed").get("ageSeconds").isNull()).isTrue();
    }

    @Test
    void aHeartbeatCarriesNoEventIdSoItCannotMoveWhereAClientResumesFrom() throws Exception {
        Outbound outbound = renderer(StreamFilter.none())
                .heartbeat(new StreamRenderer.Heartbeat(FEED, 99, NOW));

        assertThat(outbound.name()).isEqualTo("heartbeat");
        assertThat(outbound.id()).isNull();
        assertThat(body(outbound).get("latestEventId").asLong()).isEqualTo(99);
        assertThat(body(outbound).get("feed").get("ageSeconds").asLong()).isEqualTo(20);
    }

    // --- deltas -------------------------------------------------------------------------------

    @Test
    void aDeltaCarriesChangedVehiclesAndRemovedIds() throws Exception {
        StreamEvent event = delta(
                List.of(new Change(tracked("a", "501", 100), null)),
                List.of(new Gone("b", tracked("b", "501", 90).where())));

        List<Outbound> out = renderer(StreamFilter.none()).render(event);

        assertThat(out).singleElement().satisfies(outbound -> {
            assertThat(outbound.name()).isEqualTo("vehicles");
            assertThat(outbound.id()).isEqualTo("7");
        });
        JsonNode body = body(out.get(0));
        assertThat(body.get("changed")).hasSize(1);
        assertThat(body.get("changed").get(0).get(0).asText()).isEqualTo("a");
        assertThat(body.get("removed")).extracting(JsonNode::asText).containsExactly("b");
    }

    @Test
    void aFilteredClientIsSentOnlyTheVehiclesItAskedFor() throws Exception {
        StreamEvent event = delta(
                List.of(new Change(tracked("a", "501", 100), null), new Change(tracked("b", "504", 100), null)),
                List.of());

        JsonNode body = body(renderer(routes("501")).render(event).get(0));

        assertThat(body.get("changed")).hasSize(1);
        assertThat(body.get("changed").get(0).get(0).asText()).isEqualTo("a");
    }

    @Test
    void aVehicleThatLeftTheClientsFilterIsRemovedBecauseTheClientHadIt() throws Exception {
        // It was on 501 and is now on 504: it must leave a map that follows 501.
        Tracked nowOn504 = tracked("a", "504", 100);
        StreamEvent event = delta(List.of(new Change(nowOn504, tracked("a", "501", 80).where())), List.of());

        JsonNode body = body(renderer(routes("501")).render(event).get(0));

        assertThat(body.get("changed")).isEmpty();
        assertThat(body.get("removed")).extracting(JsonNode::asText).containsExactly("a");
    }

    @Test
    void aVehicleOutsideTheFilterBeforeAndAfterIsNeverMentioned() {
        StreamEvent event = delta(
                List.of(new Change(tracked("a", "504", 100), tracked("a", "504", 80).where())), List.of());

        // Nothing the client could see changed, so nothing is sent at all.
        assertThat(renderer(routes("501")).render(event)).isEmpty();
    }

    @Test
    void aNewVehicleOutsideTheFilterIsNotSentAndNotRemoved() {
        StreamEvent event = delta(List.of(new Change(tracked("a", "504", 100), null)), List.of());

        assertThat(renderer(routes("501")).render(event)).isEmpty();
    }

    @Test
    void aVehicleThatStoppedReportingIsRemovedOnlyFromClientsThatHadIt() throws Exception {
        StreamEvent event = delta(
                List.of(),
                List.of(new Gone("a", tracked("a", "501", 80).where()), new Gone("b", tracked("b", "504", 80).where())));

        JsonNode body = body(renderer(routes("501")).render(event).get(0));

        assertThat(body.get("removed")).extracting(JsonNode::asText).containsExactly("a");
    }

    @Test
    void anAreaFilterRemovesAVehicleThatDroveOutOfIt() throws Exception {
        StreamFilter inDowntown = new StreamFilter(Set.of(), BoundingBox.parse("-79.40,43.64,-79.36,43.66"));
        Tracked outside = tracked("a", "501", 43.70, -79.38, 100);
        StreamEvent event = delta(List.of(new Change(outside, tracked("a", "501", LAT, LON, 80).where())), List.of());

        JsonNode body = body(renderer(inDowntown).render(event).get(0));

        assertThat(body.get("changed")).isEmpty();
        assertThat(body.get("removed")).extracting(JsonNode::asText).containsExactly("a");
    }

    // --- snapshots and the network stream ----------------------------------------------------

    @Test
    void aSnapshotEventReplacesWhatTheClientHoldsAndIsFiltered() throws Exception {
        StreamEvent event = new StreamEvent(
                9, 8, StreamEvent.Kind.SNAPSHOT, FEED, 2, 2,
                List.of(tracked("a", "501", 100), tracked("b", "504", 100)), List.of(), List.of());

        List<Outbound> out = renderer(routes("504")).render(event);

        assertThat(out).singleElement().satisfies(outbound -> assertThat(outbound.name()).isEqualTo("snapshot"));
        assertThat(body(out.get(0)).get("vehicles")).hasSize(1);
        assertThat(body(out.get(0)).get("vehicles").get(0).get(0).asText()).isEqualTo("b");
    }

    @Test
    void theNetworkStreamReportsCountsAndLeavesAlertsUnmeasured() throws Exception {
        StreamEvent event = delta(List.of(), List.of());

        Outbound outbound = new NetworkRenderer(CLOCK).render(event).get(0);

        assertThat(outbound.name()).isEqualTo("network");
        // No id: each message is the whole status, so there is nothing to resume.
        assertThat(outbound.id()).isNull();
        JsonNode body = body(outbound);
        assertThat(body.get("vehiclesTracked").asInt()).isEqualTo(10);
        assertThat(body.get("routesReporting").asInt()).isEqualTo(2);
        // Null, not zero: alerts are not counted yet.
        assertThat(body.get("activeAlerts").isNull()).isTrue();
    }
}
