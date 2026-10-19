# Authentication and Favourites

What an account is for, how signing in works, what each part defends against, and what it does not.

Figures are measured and dated; speed is also in [performance.md](performance.md).

---

## What an account is for

Saving routes and stops. Nothing else in Trackline needs one: every page, every read endpoint and the live stream work
for someone who has never signed in, and the tests check that (`PublicEndpointsStayOpenTest` calls each public address
with no token, a garbage token and an expired one, and none may answer 401 or 403). Only two addresses ask for a token:
the account itself (`GET /api/v1/auth/me`) and the favourites (`/api/v1/favourites`).

Out of scope, and said so: sign-in with another provider, email verification, password reset, changing a password or an
address, deleting an account from the interface, and any kind of administrator.

## How sign-in works

Signing in, or registering, gives two tokens:

| | Access token | Refresh token |
| --- | --- | --- |
| What it is | a signed JWT (HMAC-SHA256) | 256 random bits |
| Lasts | 15 minutes | 30 days |
| Holds | a user id, issue time, expiry, a random id, and the mark `token_use: access` | nothing; it is only a key |
| Checked by | its signature, algorithm, issuer, expiry and mark, with no database read | its hash in the database |
| Can be taken back | no, it is good until it expires | yes |
| Stored | in the page's memory only | as a SHA-256 hash; the token itself is never stored |

An access token cannot be taken back, which is why it lasts minutes. Signing out ends the refresh token, so the person
cannot get another; the access token already out stays good until it runs down.

### Refresh tokens are used once

Exchanging a refresh token ends it and issues the next in the same family. A token presented again after it was exchanged is
either a client that lost the reply or a thief, and the server cannot tell which. So past a short leeway (10 seconds) it ends
the whole family, newest token included, and the person signs in again. That limits what a stolen token is worth to one use
by whoever is second.

- **The leeway exists for two tabs.** Two tabs refreshing together send the same token. A repeat within 10 seconds is given
  a token of its own in the same family and is not treated as theft; the tests run two requests at once and check neither ends
  the sign-in.
- **Which request wins is decided by the database.** Marking a token exchanged is one update with the condition in it, so of
  two requests carrying the same token exactly one is told it succeeded.
- **The browser renews one request at a time.** The page asks for a new token about a minute before the old one expires, and
  however many parts of it want a token at once, one request is made.

### Where the tokens live in the browser

The browser does not call the API to sign in. It calls the web application's own endpoints (`/api/auth/login`, `register`,
`refresh` and `logout`), which call the API and split the answer:

- the **access token** goes back in the body, into the page's memory;
- the **refresh token** goes into a cookie that is `HttpOnly` (a script in the page cannot read it, whatever is injected into
  the page), `SameSite=Lax` (not sent with a request another site causes), `Path=/api/auth` (sent to nowhere else) and `Secure`
  in production.

Nothing is written to `localStorage` or `sessionStorage`; checked in the browser after signing in. The result is that a script
injected into a page can use the access token for up to 15 minutes while the page is open, and cannot take the credential that
lasts a month.

The endpoints also refuse a POST whose `Origin` is another site, and say "no session" without asking the API when there is no
cookie. A refusal from the API ends the session and clears the cookie; an API that could not be reached does not, since that says
nothing about whether the person is still signed in.

## What each part defends against

| Threat | Defence | Where it is tested |
| --- | --- | --- |
| A copy of the database | passwords are BCrypt hashes (cost 12, each with its own salt); refresh tokens are stored hashed | `AuthApiIntegrationTest` reads the table |
| A guessed password | BCrypt cost, a 10-character minimum, and a limit of 5 failures per address per 15 minutes, then 429 with `Retry-After` | `LoginThrottleTest`, `AuthApiIntegrationTest` |
| Learning who has an account from sign-in | a wrong password and an unknown address give the same response, and take the same time (a decoy hash is checked for an unknown address); an unknown address is throttled like a known one | `aWrongPasswordAndAnUnknownAddressAreTheSameAnswer`; timing under Measured |
| A forged access token | the signature, algorithm (a token claiming `none` is refused), issuer and mark are checked; the secret has no default and must be 32 bytes | `AccessTokensTest` |
| A refresh token reused or stolen | single use, family revoked on reuse | `AuthApiIntegrationTest` |
| One person reading or changing another's favourites | the user id comes from the token and nowhere else, and every statement carries it | `FavouritesApiIntegrationTest` |
| A script injected into a page | the long-lived credential is in an HttpOnly cookie; the short-lived one is in memory | the route-handler tests, and the browser |
| A request another site causes | the API holds no session or cookie, so there is nothing to ride on; the web endpoints refuse a cross-site `Origin` and the cookie is `SameSite=Lax` | `routeHandlers.test.ts` |
| A sign-in link that redirects elsewhere | only a path on this site is followed after signing in | `safeNext.test.ts` |
| An over-long password silently cut | BCrypt reads 72 bytes, so a longer password is refused instead of stored as a hash of its beginning | `PasswordHasherTest` |
| A secret reaching a log | request and token records print `[redacted]`, the settings print without the secret, and the code logs no address, password or token | by construction; no test |

### Authorization, in one rule

There is no address that takes a user id. `GET /favourites`, `PUT` and `DELETE /favourites/routes/{id}` and `/stops/{id}` act on
the person the access token names. Nothing in a path, query, header or body can name anyone else, so there is nothing to check
that could be forgotten. The test that matters (`anotherPersonRemovingTheSameRouteLeavesTheFirstPersonsAlone`) has one person
remove a route and a stop that another has also saved, and checks the other's list is whole. A mutation that dropped the user
from the delete statement made it fail.

## Passwords

At least 10 characters and at most 72 bytes. No rule about digits or symbols: those make people predictable and do not make
a password hard to guess. Length is counted in characters (an emoji is one), and the upper limit in bytes (an accented letter is
two), because BCrypt ignores everything past byte 72.

## Favourites

Up to 100 routes and 100 stops each. Saving and removing are idempotent: saving what is saved, or removing what is not, succeeds
the same way, so a client that repeats a request after a lost reply is not told it failed. Saving an unknown route or stop is a
404. A saved id the current schedule no longer lists is kept and returned with a null route or stop, since a route can leave one
schedule and return in the next, and a person's list must survive an import. The ids are not foreign keys for that reason.

In the interface a signed-in person sees a Save button on the selected route and a star beside each of its stops, and a
"Your saved routes and stops" section on the home page with how each saved route is running now. A visitor sees a "Sign in to
save this route" link on a route and nothing on the stops or the home page.

## Where it differs from the plan

| Plan | Built | Why |
| --- | --- | --- |
| A Playwright journey ending in saving a favourite | not built | End-to-end tests need a seeded backend and are the plan's milestone 18. The same journey was checked by hand in the browser, and each step is covered by a unit or integration test. |
| Tokens straight from the API to the browser | a small layer in the web application that holds the refresh token in a cookie | The API's response would otherwise put a month-long credential where any injected script could read it. |
| `/auth/*` and `/favourites` in the freshness envelope | plain bodies | There is no feed behind them to be fresh or stale. |

## Known limitations

- **Registering says whether an address is taken.** Hiding it needs a confirmation email, which is not built, and a registration
  that silently did nothing would leave the person with no account and no explanation.
- **The failure limit is by address, in memory.** Behind the web application every request arrives from one place, so a limit by
  source would throttle everyone for one person's guessing. So someone can lock a named account for 15 minutes by failing against
  it on purpose. It forgets on restart, does not span instances, and is bounded at 10,000 addresses, so a flood of other
  addresses can push an entry out. Limiting by source belongs in front of the application, in milestone 18.
- **An access token cannot be taken back.** Signing out, or the account being deleted, leaves a token that has already been
  issued working for up to 15 minutes. `/auth/me` answers 401 once the account is gone.
- **No account recovery.** A forgotten password cannot be reset.
- **The schedule import endpoint is still open.** `POST /api/v1/admin/gtfs/import` has been open since milestone 5 and this milestone
  does not close it: there is no administrator to give it to. It is disabled by `trackline.gtfs.static.admin-endpoint=false`,
  which must be set wherever the API is reachable by anyone else.
- **Expired refresh tokens are kept for 7 days** and then deleted by the retention job.
- **No one has had the security of this reviewed by someone else.**

## Measured

On the development laptop, 2026-10-18 to 19, the API from source against the local database, over `curl` to localhost with
BCrypt at the shipped cost of 12. Small samples: read them as magnitudes.

| | Min | Median | Max |
| --- | ---: | ---: | ---: |
| Sign in, correct password (10) | 265 ms | 296 ms | 333 ms |
| Sign in, wrong password (12) | 277 ms | 315 ms | 329 ms |
| Sign in, address with no account (12) | 273 ms | 310 ms | 422 ms |
| `GET /favourites` with a valid token (25) | 6.8 ms | 8.0 ms | 37.4 ms |
| Register (3) | 307 ms | 331 ms | 390 ms |

BCrypt is nearly all of a sign-in. The three sign-in rows are within the noise of one another, which is what the decoy hash
is for: the time a sign-in takes does not say whether the address is registered. That is a check at one cost on one machine, not a
proof against a careful measurement.

## An error found on the way

While testing the sign-in errors in the full application: an error's extra fields (`errorId`, `resourceId`, `violations`,
`timestamp`) came out nested under a `properties` object in every response, since milestone 9. The controller tests missed it,
because their test slice uses a different JSON converter that writes them at the top level, so the contract they checked was
not the one the running application served; the web client reads `errorId` from the top level and never found one. The cause
was a second `ObjectMapper` bean (the live-state cache's), which made Spring Boot skip building the web one. The cache mapper is
now held in a wrapper type so it no longer replaces the web mapper, and a test checks the shape in the full application.
