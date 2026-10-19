package ca.trackline.api.auth;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static ca.trackline.api.testsupport.ProjectionFixtures.clear;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertFeedVersion;
import static ca.trackline.api.testsupport.ProjectionFixtures.insertRoute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Saved routes and stops: only for the signed-in person, only theirs, and never reachable by naming someone else.
 */
class FavouritesApiIntegrationTest extends AuthIntegrationTest {

    private long feed;

    @BeforeEach
    void seedTheSchedule() {
        clear(jdbc);
        feed = insertFeedVersion(jdbc);
        insertRoute(jdbc, feed, "501", "501", "Queen", 0);
        insertRoute(jdbc, feed, "504", "504", "King", 0);
        insertRoute(jdbc, feed, "7", "7", "Bathurst", 3);
        jdbc.update("UPDATE routes SET color = 'DA251D', text_color = 'FFFFFF' WHERE route_id = '501'");
        stop("3718", "Union Station");
        stop("9001", "Woodbine Ave");
    }

    @AfterEach
    void cleanUp() {
        clear(jdbc);
    }

    private void stop(String id, String name) {
        jdbc.update(
                """
                INSERT INTO stops (stop_id, feed_version_id, code, name, location)
                VALUES (?, ?, '1', ?, ST_SetSRID(ST_MakePoint(-79.38, 43.64), 4326)::geography)
                """,
                id, feed, name);
    }

    private MockHttpServletRequestBuilder as(JsonNode tokens, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, bearer(tokens));
    }

    private JsonNode favourites(JsonNode tokens) throws Exception {
        MvcResult result = mvc.perform(as(tokens, get("/api/v1/favourites"))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    // --- who may ask ------------------------------------------------------------------------------------------

    @Test
    void everyFavouritesAddressNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/favourites")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/favourites/routes/501")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/favourites/routes/501")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/favourites/stops/3718")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/favourites/stops/3718")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBadOrExpiredTokenIsRefusedToo() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(get("/api/v1/favourites").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());

        CLOCK.advance(Duration.ofMinutes(16));

        mvc.perform(as(tokens, get("/api/v1/favourites"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aWriteWithoutATokenChangesNothing() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(put("/api/v1/favourites/routes/501")).andExpect(status().isUnauthorized());

        assertThat(favourites(tokens).get("routes")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_routes", Integer.class)).isZero();
    }

    // --- one person's list ------------------------------------------------------------------------------------

    @Test
    void aNewAccountHasNothingSaved() throws Exception {
        JsonNode view = favourites(register(newEmail()));

        assertThat(view.get("routes")).isEmpty();
        assertThat(view.get("stops")).isEmpty();
    }

    @Test
    void savingARouteThenListingItShowsWhatTheScheduleCallsIt() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

        mvc.perform(as(tokens, get("/api/v1/favourites")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.routes.length()").value(1))
                .andExpect(jsonPath("$.routes[0].routeId").value("501"))
                .andExpect(jsonPath("$.routes[0].route.shortName").value("501"))
                .andExpect(jsonPath("$.routes[0].route.longName").value("Queen"))
                .andExpect(jsonPath("$.routes[0].route.colour").value("DA251D"))
                .andExpect(jsonPath("$.routes[0].savedAt").isNotEmpty());
    }

    @Test
    void savingAStopShowsItsNameAndPlace() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(as(tokens, put("/api/v1/favourites/stops/3718"))).andExpect(status().isNoContent());

        mvc.perform(as(tokens, get("/api/v1/favourites")))
                .andExpect(jsonPath("$.stops.length()").value(1))
                .andExpect(jsonPath("$.stops[0].stopId").value("3718"))
                .andExpect(jsonPath("$.stops[0].stop.name").value("Union Station"))
                .andExpect(jsonPath("$.stops[0].stop.latitude").value(43.64));
    }

    @Test
    void savingTwiceIsTheSameAsOnceAndKeepsTheFirstTime() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());
        String first = favourites(tokens).get("routes").get(0).get("savedAt").asText();

        // Inside the access token's fifteen minutes, so the same token still works.
        CLOCK.advance(Duration.ofMinutes(5));
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());

        JsonNode routes = favourites(tokens).get("routes");
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).get("savedAt").asText()).isEqualTo(first);
    }

    @Test
    void removingIsIdempotentToo() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501")));

        mvc.perform(as(tokens, delete("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());
        mvc.perform(as(tokens, delete("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());
        // Never saved at all.
        mvc.perform(as(tokens, delete("/api/v1/favourites/routes/504"))).andExpect(status().isNoContent());
        mvc.perform(as(tokens, delete("/api/v1/favourites/stops/3718"))).andExpect(status().isNoContent());

        assertThat(favourites(tokens).get("routes")).isEmpty();
    }

    @Test
    void theNewestSavedComesFirst() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501")));
        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/7")));
        CLOCK.advance(Duration.ofMinutes(1));
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/504")));

        List<String> order = new java.util.ArrayList<>();
        favourites(tokens).get("routes").forEach(saved -> order.add(saved.get("routeId").asText()));

        assertThat(order).containsExactly("504", "7", "501");
    }

    @Test
    void anUnknownRouteOrStopIsNotFoundAndIsNotSaved() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(as(tokens, put("/api/v1/favourites/routes/nope")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceId").value("nope"));
        mvc.perform(as(tokens, put("/api/v1/favourites/stops/nope"))).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_stops", Integer.class)).isZero();
    }

    // --- one person cannot reach another's -------------------------------------------------------------------

    @Test
    void anotherPersonDoesNotSeeWhatSomeoneSaved() throws Exception {
        JsonNode alice = register(newEmail());
        JsonNode bob = register(newEmail());
        mvc.perform(as(alice, put("/api/v1/favourites/routes/501")));
        mvc.perform(as(alice, put("/api/v1/favourites/stops/3718")));

        JsonNode view = favourites(bob);

        assertThat(view.get("routes")).isEmpty();
        assertThat(view.get("stops")).isEmpty();
        assertThat(favourites(alice).get("routes")).hasSize(1);
    }

    @Test
    void anotherPersonRemovingTheSameRouteLeavesTheFirstPersonsAlone() throws Exception {
        JsonNode alice = register(newEmail());
        JsonNode bob = register(newEmail());
        mvc.perform(as(alice, put("/api/v1/favourites/routes/501")));
        mvc.perform(as(alice, put("/api/v1/favourites/stops/3718")));
        mvc.perform(as(bob, put("/api/v1/favourites/routes/501")));

        // Bob removes route 501 and stop 3718, which Alice also has saved.
        mvc.perform(as(bob, delete("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());
        mvc.perform(as(bob, delete("/api/v1/favourites/stops/3718"))).andExpect(status().isNoContent());

        JsonNode aliceView = favourites(alice);
        assertThat(aliceView.get("routes").get(0).get("routeId").asText()).isEqualTo("501");
        assertThat(aliceView.get("stops").get(0).get("stopId").asText()).isEqualTo("3718");
        assertThat(favourites(bob).get("routes")).isEmpty();
    }

    @Test
    void theSameRouteSavedByTwoPeopleIsTwoSeparateRows() throws Exception {
        JsonNode alice = register(newEmail());
        JsonNode bob = register(newEmail());

        mvc.perform(as(alice, put("/api/v1/favourites/routes/501")));
        mvc.perform(as(bob, put("/api/v1/favourites/routes/501")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_routes WHERE route_id = '501'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void thereIsNoAddressThatTakesAUserIdSoAnotherPersonsListCannotBeAskedFor() throws Exception {
        JsonNode alice = register(newEmail());
        JsonNode bob = register(newEmail());
        mvc.perform(as(alice, put("/api/v1/favourites/routes/501")));
        String aliceId = alice.get("user").get("id").asText();

        // Naming her in a path, a query or a header changes nothing: whose list it is comes from the token alone.
        for (String path : List.of(
                "/api/v1/favourites?userId=" + aliceId,
                "/api/v1/favourites/" + aliceId,
                "/api/v1/users/" + aliceId + "/favourites")) {
            MvcResult result = mvc.perform(as(bob, get(path).header("X-User-Id", aliceId))).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("501");
        }
        assertThat(favourites(bob).get("routes")).isEmpty();
    }

    @Test
    void aTokenForSomeoneElsesIdCannotBeMadeWithoutTheKey() throws Exception {
        JsonNode alice = register(newEmail());
        mvc.perform(as(alice, put("/api/v1/favourites/routes/501")));
        // A token naming Alice's id but signed with a key the server does not hold.
        String forged = new AccessTokens(
                new AuthProperties("an-attackers-own-key-that-is-long-enough-000000", "trackline",
                        Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(10), 4, 10, 5,
                        Duration.ofMinutes(15)),
                CLOCK).issue(UUID.fromString(alice.get("user").get("id").asText())).value();

        mvc.perform(get("/api/v1/favourites").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    // --- bounds and survival -----------------------------------------------------------------------------------

    @Test
    void aPersonMaySaveAHundredRoutesAndNotAHundredAndOne() throws Exception {
        JsonNode tokens = register(newEmail());
        for (int index = 0; index < 101; index++) {
            insertRoute(jdbc, feed, "bulk-" + index, "B" + index, "Bulk " + index, 3);
        }
        for (int index = 0; index < 100; index++) {
            mvc.perform(as(tokens, put("/api/v1/favourites/routes/bulk-" + index))).andExpect(status().isNoContent());
        }

        mvc.perform(as(tokens, put("/api/v1/favourites/routes/bulk-100")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("at most 100 routes")));
        // Saving what is already saved still works at the limit.
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/bulk-0"))).andExpect(status().isNoContent());
        // And making room makes room.
        mvc.perform(as(tokens, delete("/api/v1/favourites/routes/bulk-0")));
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/bulk-100"))).andExpect(status().isNoContent());

        assertThat(favourites(tokens).get("routes")).hasSize(100);
    }

    @Test
    void theLimitIsPerKindAndPerPerson() throws Exception {
        JsonNode full = register(newEmail());
        JsonNode other = register(newEmail());
        for (int index = 0; index < 100; index++) {
            insertRoute(jdbc, feed, "bulk-" + index, "B" + index, "Bulk " + index, 3);
            mvc.perform(as(full, put("/api/v1/favourites/routes/bulk-" + index)));
        }

        // A full list of routes does not stop saving a stop, and does not stop anyone else saving a route.
        mvc.perform(as(full, put("/api/v1/favourites/stops/3718"))).andExpect(status().isNoContent());
        mvc.perform(as(other, put("/api/v1/favourites/routes/501"))).andExpect(status().isNoContent());
    }

    @Test
    void aSavedRouteTheScheduleNoLongerListsIsKeptAndSaidToBeUnknown() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501")));
        mvc.perform(as(tokens, put("/api/v1/favourites/stops/3718")));

        // A schedule import that no longer has them.
        jdbc.update("DELETE FROM routes WHERE route_id = '501'");
        jdbc.update("DELETE FROM stops WHERE stop_id = '3718'");

        mvc.perform(as(tokens, get("/api/v1/favourites")))
                .andExpect(jsonPath("$.routes[0].routeId").value("501"))
                .andExpect(jsonPath("$.routes[0].route").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stops[0].stopId").value("3718"))
                .andExpect(jsonPath("$.stops[0].stop").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void deletingAnAccountRemovesItsFavouritesAndItsTokens() throws Exception {
        JsonNode tokens = register(newEmail());
        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501")));
        mvc.perform(as(tokens, put("/api/v1/favourites/stops/3718")));

        jdbc.update("DELETE FROM users");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favourite_stops", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
    }

    @Test
    void anAddressContainingAnInjectionAttemptIsJustAnUnknownRoute() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(as(tokens, put("/api/v1/favourites/routes/501' OR '1'='1")))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
    }
}
