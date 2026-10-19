package ca.trackline.api.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-in over HTTP, against the real filter chain and database: registering, signing in, the tokens, refreshing,
 * signing out, and the ways each is refused.
 */
class AuthApiIntegrationTest extends AuthIntegrationTest {

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int refreshStatus(String refreshToken) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // --- registering ----------------------------------------------------------------------------------------

    @Test
    void registeringCreatesTheAccountAndSignsInWithATokenPair() throws Exception {
        String email = newEmail();

        mvc.perform(postJson("/api/v1/auth/register", credentials(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshExpiresIn").value(30 * 24 * 3600))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void theResponseNeverContainsThePasswordOrItsHash() throws Exception {
        String body = mvc.perform(postJson("/api/v1/auth/register", credentials(newEmail(), PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(PASSWORD).doesNotContain("password").doesNotContain("$2a$").doesNotContain("$2b$");
    }

    @Test
    void noCookieIsEverSet() throws Exception {
        MvcResult result = mvc.perform(postJson("/api/v1/auth/register", credentials(newEmail(), PASSWORD))).andReturn();

        // The API holds no session. The refresh token travels in a body and is kept in a cookie by the web application.
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHashAndTheRefreshTokenOnlyAsAHash() throws Exception {
        String email = newEmail();
        JsonNode tokens = register(email);

        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(stored).startsWith("$2a$04$").doesNotContain(PASSWORD);

        String refreshToken = tokens.get("refreshToken").asText();
        List<String> hashes = jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.getFirst()).matches("[0-9a-f]{64}").isNotEqualTo(refreshToken);
        // The token itself is nowhere in the table.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, refreshToken)).isZero();
    }

    @Test
    void anAddressIsStoredTrimmedAndInLowerCase() throws Exception {
        String email = newEmail();

        mvc.perform(postJson("/api/v1/auth/register", credentials("  " + email.toUpperCase() + " ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isOne();
    }

    @Test
    void anAddressAlreadyRegisteredIsAConflictWhateverItsCase() throws Exception {
        String email = newEmail();
        register(email);

        mvc.perform(postJson("/api/v1/auth/register", credentials(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/email-already-registered"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
    }

    @Test
    void twoRegistrationsForOneAddressArrivingTogetherCreateOneAccount() throws Exception {
        String email = newEmail();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> attempts = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Callable<Integer> attempt = () -> {
                go.await();
                return mvc.perform(postJson("/api/v1/auth/register", credentials(email, PASSWORD)))
                        .andReturn().getResponse().getStatus();
            };
            attempts.add(pool.submit(attempt));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> attempt : attempts) {
            statuses.add(attempt.get());
        }
        pool.shutdown();

        // The unique index decides, not a check made first, so exactly one wins.
        assertThat(statuses).containsOnlyOnce(201).filteredOn(code -> code != 201).containsOnly(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isOne();
    }

    @Test
    void registrationRefusesWhatIsNotAnAddressOrAnAcceptablePasswordAndCreatesNothing() throws Exception {
        String[][] bad = {
            {"not-an-address", PASSWORD},
            {"missing-domain@", PASSWORD},
            {"a@b", PASSWORD},
            {"two@@example.test", PASSWORD},
            {"space in@example.test", PASSWORD},
            {"x".repeat(250) + "@example.test", PASSWORD},
            {newEmail(), "short-pw"},
            {newEmail(), "a".repeat(73)},
            {newEmail(), ""},
        };
        for (String[] attempt : bad) {
            mvc.perform(postJson("/api/v1/auth/register", credentials(attempt[0], attempt[1])))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void aMissingOrMalformedBodyIsABadRequest() throws Exception {
        mvc.perform(postJson("/api/v1/auth/register", "")).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/auth/register", "{not json")).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/auth/register", "{\"email\":\"" + newEmail() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/auth/login", "{}")).andExpect(status().isBadRequest());
    }

    // --- signing in -----------------------------------------------------------------------------------------

    @Test
    void signingInWithTheRightPasswordReturnsTokensAndRecordsWhen() throws Exception {
        String email = newEmail();
        register(email);
        CLOCK.advance(Duration.ofHours(1));

        mvc.perform(postJson("/api/v1/auth/login", credentials(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email));

        assertThat(jdbc.queryForObject("SELECT last_login_at FROM users WHERE email = ?", java.sql.Timestamp.class, email)
                .toInstant()).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    @Test
    void theAddressIsCaseInsensitiveWhenSigningIn() throws Exception {
        String email = newEmail();
        register(email);

        mvc.perform(postJson("/api/v1/auth/login", credentials(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordAndAnUnknownAddressAreTheSameAnswer() throws Exception {
        String email = newEmail();
        register(email);

        MvcResult wrongPassword = mvc.perform(postJson("/api/v1/auth/login", credentials(email, "not-the-password-0")))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult unknownAddress = mvc.perform(postJson("/api/v1/auth/login", credentials(newEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized()).andReturn();

        // The same status, type, title and words, so the response cannot be used to find out who has an account.
        JsonNode first = json.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode second = json.readTree(unknownAddress.getResponse().getContentAsString());
        for (String field : List.of("type", "title", "status", "detail")) {
            assertThat(first.get(field)).isEqualTo(second.get(field));
        }
        assertThat(first.get("detail").asText()).isEqualTo("Invalid email or password.");
    }

    @Test
    void afterFiveFailuresEvenTheRightPasswordIsRefusedWithHowLongToWait() throws Exception {
        String email = newEmail();
        register(email);
        for (int index = 0; index < 5; index++) {
            mvc.perform(postJson("/api/v1/auth/login", credentials(email, "wrong-password-" + index)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(postJson("/api/v1/auth/login", credentials(email, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/too-many-attempts"));
    }

    @Test
    void theAddressOpensAgainOnceTheWindowHasPassed() throws Exception {
        String email = newEmail();
        register(email);
        for (int index = 0; index < 5; index++) {
            mvc.perform(postJson("/api/v1/auth/login", credentials(email, "wrong-password-" + index)));
        }

        CLOCK.advance(Duration.ofMinutes(15));

        mvc.perform(postJson("/api/v1/auth/login", credentials(email, PASSWORD))).andExpect(status().isOk());
    }

    @Test
    void anAddressWithNoAccountIsShutAfterFiveFailuresToo() throws Exception {
        // Otherwise the refusal would itself say which addresses are registered.
        String nobody = newEmail();
        for (int index = 0; index < 5; index++) {
            mvc.perform(postJson("/api/v1/auth/login", credentials(nobody, PASSWORD))).andExpect(status().isUnauthorized());
        }

        mvc.perform(postJson("/api/v1/auth/login", credentials(nobody, PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulSignInClearsTheFailuresSoGoodPeopleAreNotSlowlyLockedOut() throws Exception {
        String email = newEmail();
        register(email);
        for (int round = 0; round < 3; round++) {
            for (int index = 0; index < 4; index++) {
                mvc.perform(postJson("/api/v1/auth/login", credentials(email, "wrong-password-" + index)));
            }
            mvc.perform(postJson("/api/v1/auth/login", credentials(email, PASSWORD))).andExpect(status().isOk());
        }
    }

    // --- the signed-in account ------------------------------------------------------------------------------

    @Test
    void theAccountEndpointAnswersForAValidAccessToken() throws Exception {
        String email = newEmail();
        JsonNode tokens = register(email);

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.id").value(tokens.get("user").get("id").asText()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void withoutATokenTheAnswerIsAProblemThatNamesTheSchemeAndSaysNothingMore() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/authentication-required"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anExpiredTokenAndAGarbageTokenGetTheSameAnswerAsNoToken() throws Exception {
        JsonNode tokens = register(newEmail());
        CLOCK.advance(Duration.ofMinutes(16));

        MvcResult expired = mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult garbage = mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
                .andExpect(status().isUnauthorized()).andReturn();

        // Why a token was refused is not told to whoever presented it.
        for (MvcResult result : List.of(expired, garbage)) {
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("detail").asText()).isEqualTo("A valid access token is required.");
            assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        }
    }

    @Test
    void aRefreshTokenCannotBePresentedAsAnAccessToken() throws Exception {
        JsonNode tokens = register(newEmail());

        mvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenForAnAccountThatHasSinceGoneIsRefused() throws Exception {
        JsonNode tokens = register(newEmail());
        jdbc.update("DELETE FROM users");

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/account-gone"));
    }

    // --- refreshing and signing out -------------------------------------------------------------------------

    @Test
    void refreshingGivesANewPairAndTheOldRefreshTokenStopsWorkingOnceTheLeewayHasPassed() throws Exception {
        JsonNode first = register(newEmail());
        String oldRefresh = first.get("refreshToken").asText();

        JsonNode second = refresh(oldRefresh);

        assertThat(second.get("refreshToken").asText()).isNotEqualTo(oldRefresh);
        assertThat(second.get("accessToken").asText()).isNotEqualTo(first.get("accessToken").asText());
        CLOCK.advance(Duration.ofSeconds(11));
        assertThat(refreshStatus(oldRefresh)).isEqualTo(401);
    }

    @Test
    void anOldRefreshTokenPresentedAgainBeyondTheLeewayEndsTheWholeSignInIncludingTheNewestToken() throws Exception {
        JsonNode first = register(newEmail());
        String stolen = first.get("refreshToken").asText();
        String owners = refresh(stolen).get("refreshToken").asText();
        CLOCK.advance(Duration.ofSeconds(11));

        // Someone has the old token. They cannot tell whether the person already used it, and neither can the server.
        assertThat(refreshStatus(stolen)).isEqualTo(401);

        // So the family is over, and the person's newest token is dead with it: they sign in again.
        assertThat(refreshStatus(owners)).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void aRepeatInsideTheLeewayIsNotTreatedAsTheftAndTheSignInSurvives() throws Exception {
        JsonNode first = register(newEmail());
        String token = first.get("refreshToken").asText();
        refresh(token);
        CLOCK.advance(Duration.ofSeconds(10));

        // A second tab, or a reply that was lost on the way back, a moment after the first exchange.
        MvcResult repeat = mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andReturn();

        String sibling = json.readTree(repeat.getResponse().getContentAsString()).get("refreshToken").asText();
        assertThat(refreshStatus(sibling)).isEqualTo(200);
    }

    @Test
    void aRefreshChainCanGoOnForAsLongAsItIsUsed() throws Exception {
        JsonNode tokens = register(newEmail());
        String current = tokens.get("refreshToken").asText();
        for (int index = 0; index < 5; index++) {
            CLOCK.advance(Duration.ofDays(1));
            current = refresh(current).get("refreshToken").asText();
        }

        assertThat(current).isNotBlank();
        // Six tokens in one family, and only the newest unused.
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT family_id) FROM refresh_tokens", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE rotated_at IS NULL", Integer.class)).isOne();
    }

    @Test
    void aRefreshTokenUnusedForLongerThanItsLifetimeIsRefused() throws Exception {
        JsonNode tokens = register(newEmail());
        String token = tokens.get("refreshToken").asText();

        CLOCK.advance(Duration.ofDays(30).minusSeconds(1));
        assertThat(refreshStatus(token)).isEqualTo(200);

        JsonNode second = register(newEmail());
        CLOCK.advance(Duration.ofDays(30));
        assertThat(refreshStatus(second.get("refreshToken").asText())).isEqualTo(401);
    }

    @Test
    void anUnknownRefreshTokenIsTheSameRefusalAsAnEndedOne() throws Exception {
        mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + "A".repeat(43) + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://trackline.ca/problems/session-ended"));
    }

    @Test
    void aBlankRefreshTokenIsABadRequest() throws Exception {
        mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(postJson("/api/v1/auth/refresh", "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void twoTabsRefreshingTheSameTokenAtOnceBothGetAnswersAndNeitherEndsTheSignIn() throws Exception {
        JsonNode tokens = register(newEmail());
        String shared = tokens.get("refreshToken").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Callable<String> attempt = () -> {
                go.await();
                MvcResult result = mvc.perform(postJson("/api/v1/auth/refresh", "{\"refreshToken\":\"" + shared + "\"}"))
                        .andReturn();
                return result.getResponse().getStatus() + ":" + result.getResponse().getContentAsString();
            };
            attempts.add(pool.submit(attempt));
        }
        go.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> attempt : attempts) {
            results.add(attempt.get());
        }
        pool.shutdown();

        // The conditional update lets one exchange win, and the other is a repeat inside the leeway: not theft.
        assertThat(results).allMatch(result -> result.startsWith("200:"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void signingOutEndsTheSignInAndAnswersTheSameForATokenItDoesNotKnow() throws Exception {
        JsonNode tokens = register(newEmail());
        String token = tokens.get("refreshToken").asText();

        mvc.perform(postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + token + "\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + "B".repeat(43) + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(token)).isEqualTo(401);
    }

    @Test
    void signingOutEndsEveryTokenInTheFamilyNotJustTheOnePresented() throws Exception {
        JsonNode first = register(newEmail());
        String newest = refresh(first.get("refreshToken").asText()).get("refreshToken").asText();

        mvc.perform(postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(refreshStatus(newest)).isEqualTo(401);
    }

    @Test
    void signingOutOnePersonLeavesAnotherSignedIn() throws Exception {
        JsonNode first = register(newEmail());
        JsonNode second = register(newEmail());

        mvc.perform(postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + first.get("refreshToken").asText() + "\"}"));

        assertThat(refreshStatus(second.get("refreshToken").asText())).isEqualTo(200);
    }

    @Test
    void theSameAccountSignedInTwiceHasTwoIndependentSignIns() throws Exception {
        String email = newEmail();
        JsonNode phone = register(email);
        MvcResult laptopResult = mvc.perform(postJson("/api/v1/auth/login", credentials(email, PASSWORD))).andReturn();
        JsonNode laptop = json.readTree(laptopResult.getResponse().getContentAsString());

        mvc.perform(postJson("/api/v1/auth/logout", "{\"refreshToken\":\"" + phone.get("refreshToken").asText() + "\"}"));

        assertThat(refreshStatus(laptop.get("refreshToken").asText())).isEqualTo(200);
    }

    // --- the browser -----------------------------------------------------------------------------------------

    @Test
    void aPreflightFromTheWebApplicationMayCarryTheAuthorizationHeaderAndUseTheWriteMethods() throws Exception {
        mvc.perform(options("/api/v1/favourites/routes/501")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, org.hamcrest.Matchers.containsStringIgnoringCase("authorization")))
                // No cookie is ever sent to this API from a browser page, so credentials are not allowed.
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void aPreflightFromAnyOtherOriginIsRefused() throws Exception {
        mvc.perform(options("/api/v1/favourites/routes/501")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isForbidden());
    }
}
