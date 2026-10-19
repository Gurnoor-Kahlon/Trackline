// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { POST as login } from "@/app/api/auth/login/route";
import { POST as logout } from "@/app/api/auth/logout/route";
import { POST as refresh } from "@/app/api/auth/refresh/route";
import { POST as register } from "@/app/api/auth/register/route";

/**
 * The web application's own sign-in endpoints, which stand between the browser and the API so the refresh token can live
 * in a cookie the page cannot read.
 */

const ORIGIN = "https://trackline.test";
const BACKEND = "http://localhost:8080";

const TOKENS = {
  accessToken: "access-token-value",
  tokenType: "Bearer",
  expiresIn: 900,
  refreshToken: "refresh-token-value",
  refreshExpiresIn: 2_592_000,
  user: { id: "u-1", email: "person@example.test", createdAt: "2026-10-20T12:00:00Z" },
};

const fetchMock = vi.fn<typeof fetch>();

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
    ...init,
  });
}

function request(path: string, init: { body?: unknown; cookie?: string; origin?: string | null } = {}): Request {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (init.cookie !== undefined) {
    headers.set("cookie", init.cookie);
  }
  // A browser always names the page's origin on a POST; null leaves it out, as a script outside a browser would.
  if (init.origin !== null) {
    headers.set("origin", init.origin ?? ORIGIN);
  }
  return new Request(`${ORIGIN}${path}`, {
    method: "POST",
    headers,
    ...(init.body === undefined ? {} : { body: JSON.stringify(init.body) }),
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("signing in through the web application", () => {
  it("passes the credentials to the API and answers with the access token only", async () => {
    fetchMock.mockResolvedValue(jsonResponse(TOKENS));

    const response = await login(request("/api/auth/login", { body: { email: "p@example.test", password: "pw" } }));

    expect(fetchMock).toHaveBeenCalledWith(
      `${BACKEND}/api/v1/auth/login`,
      expect.objectContaining({ method: "POST", body: JSON.stringify({ email: "p@example.test", password: "pw" }) }),
    );
    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({ accessToken: "access-token-value", expiresIn: 900, user: TOKENS.user });
    // The refresh token is for the cookie, and never in anything the page's script can read.
    expect(JSON.stringify(body)).not.toContain("refresh-token-value");
    expect(response.headers.get("cache-control")).toBe("no-store");
  });

  it("puts the refresh token in an HttpOnly cookie, kept to the sign-in endpoints", async () => {
    fetchMock.mockResolvedValue(jsonResponse(TOKENS));

    const response = await login(request("/api/auth/login", { body: {} }));

    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain("trackline_refresh=refresh-token-value");
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/SameSite=lax/i);
    expect(cookie).toContain("Path=/api/auth");
    expect(cookie).toContain("Max-Age=2592000");
  });

  it("marks the cookie Secure in production and not on a developer's machine", async () => {
    // A fresh response each call: a body can be read once.
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(TOKENS)));

    const development = await login(request("/api/auth/login", { body: {} }));
    vi.stubEnv("NODE_ENV", "production");
    const production = await login(request("/api/auth/login", { body: {} }));

    expect(development.headers.get("set-cookie")).not.toMatch(/Secure/i);
    expect(production.headers.get("set-cookie")).toMatch(/Secure/i);
  });

  it("registers the same way and answers 201", async () => {
    fetchMock.mockResolvedValue(jsonResponse(TOKENS, { status: 201 }));

    const response = await register(request("/api/auth/register", { body: { email: "p@example.test", password: "pw" } }));

    expect(fetchMock.mock.calls[0]?.[0]).toBe(`${BACKEND}/api/v1/auth/register`);
    expect(response.status).toBe(201);
    expect(response.headers.get("set-cookie")).toContain("trackline_refresh=");
  });

  it("passes a refusal on with its status and its words, and sets no cookie", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ title: "Sign-in failed", detail: "Invalid email or password.", status: 401 }, { status: 401 }),
    );

    const response = await login(request("/api/auth/login", { body: {} }));

    expect(response.status).toBe(401);
    expect(await response.json()).toMatchObject({ detail: "Invalid email or password." });
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("passes on how long to wait when the account has been shut", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ title: "Too many attempts", detail: "Try later." }, { status: 429, headers: { "Retry-After": "900" } }),
    );

    const response = await login(request("/api/auth/login", { body: {} }));

    expect(response.status).toBe(429);
    expect(response.headers.get("retry-after")).toBe("900");
  });

  it("says the API could not be reached when it could not, rather than failing", async () => {
    fetchMock.mockRejectedValue(new TypeError("connect ECONNREFUSED"));

    const response = await login(request("/api/auth/login", { body: {} }));

    expect(response.status).toBe(502);
    expect(await response.json()).toMatchObject({ detail: "The Trackline API could not be reached." });
  });

  it("copes with a body that is not JSON by letting the API refuse it", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ title: "Invalid request", detail: "bad" }, { status: 400 }));
    const broken = new Request(`${ORIGIN}/api/auth/login`, {
      method: "POST",
      headers: { origin: ORIGIN, "Content-Type": "application/json" },
      body: "{not json",
    });

    const response = await login(broken);

    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({});
    expect(response.status).toBe(400);
  });
});

describe("refusing a request another site caused", () => {
  it.each([
    ["login", login],
    ["register", register],
    ["refresh", refresh],
    ["logout", logout],
  ])("%s refuses a POST whose origin is another site, and never calls the API", async (_name, handler) => {
    const response = await handler(
      request("/api/auth/x", { body: {}, origin: "https://evil.example", cookie: "trackline_refresh=abc" }),
    );

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("allows a request with no origin, which did not come from a page and has no ambient cookie to abuse", async () => {
    fetchMock.mockResolvedValue(jsonResponse(TOKENS));

    const response = await login(request("/api/auth/login", { body: {}, origin: null }));

    expect(response.status).toBe(200);
  });
});

describe("renewing the session", () => {
  it("says nobody is signed in when there is no cookie, without asking the API", async () => {
    const response = await refresh(request("/api/auth/refresh"));

    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the cookie's token to the API and rotates the cookie", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ ...TOKENS, accessToken: "new-access", refreshToken: "new-refresh" }),
    );

    const response = await refresh(request("/api/auth/refresh", { cookie: "other=1; trackline_refresh=old-refresh; x=2" }));

    expect(JSON.parse(fetchMock.mock.calls[0]?.[1]?.body as string)).toEqual({ refreshToken: "old-refresh" });
    expect(await response.json()).toMatchObject({ accessToken: "new-access" });
    expect(response.headers.get("set-cookie")).toContain("trackline_refresh=new-refresh");
  });

  it("clears the cookie when the API says the session is over, so it is not presented again", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ title: "Session ended", detail: "The session has ended. Sign in again." }, { status: 401 }),
    );

    const response = await refresh(request("/api/auth/refresh", { cookie: "trackline_refresh=used-token" }));

    expect(response.status).toBe(401);
    const cookie = response.headers.get("set-cookie") ?? "";
    expect(cookie).toContain("trackline_refresh=;");
    expect(cookie).toContain("Max-Age=0");
  });

  it("leaves the cookie alone when the API merely could not be reached, since that says nothing about the session", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));

    const response = await refresh(request("/api/auth/refresh", { cookie: "trackline_refresh=still-good" }));

    expect(response.status).toBe(502);
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("leaves the cookie alone when the API failed, for the same reason", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ title: "Internal server error", detail: "x" }, { status: 500 }));

    const response = await refresh(request("/api/auth/refresh", { cookie: "trackline_refresh=still-good" }));

    expect(response.status).toBe(500);
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("ignores a cookie that is not valid encoding instead of failing", async () => {
    const response = await refresh(request("/api/auth/refresh", { cookie: "trackline_refresh=%E0%A4%A" }));

    expect(response.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("signing out", () => {
  it("ends the session at the API and removes the cookie", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    const response = await logout(request("/api/auth/logout", { cookie: "trackline_refresh=my-token" }));

    expect(fetchMock).toHaveBeenCalledWith(
      `${BACKEND}/api/v1/auth/logout`,
      expect.objectContaining({ body: JSON.stringify({ refreshToken: "my-token" }) }),
    );
    expect(response.status).toBe(204);
    expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("removes the cookie even when the API could not be reached, since the person asked to be signed out", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));

    const response = await logout(request("/api/auth/logout", { cookie: "trackline_refresh=my-token" }));

    expect(response.status).toBe(204);
    expect(response.headers.get("set-cookie")).toContain("Max-Age=0");
  });

  it("does nothing at the API when there was no cookie, and still answers the same", async () => {
    const response = await logout(request("/api/auth/logout"));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(response.status).toBe(204);
  });
});
