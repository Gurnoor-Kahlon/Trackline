import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { authenticate, endSession, renew } from "@/lib/auth/client";
import { AuthError } from "@/lib/auth/types";

const fetchMock = vi.fn<typeof fetch>();

const USER = { id: "u-1", email: "p@example.test", createdAt: "2026-10-20T12:00:00Z" };

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" }, ...init });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("signing in from the browser", () => {
  it("posts to this site's own endpoint, not the API, and sends the cookie only to this site", async () => {
    fetchMock.mockResolvedValue(json({ accessToken: "a", expiresIn: 900, user: USER }));

    await authenticate("login", "p@example.test", "pw", 1_000);

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/auth/login",
      expect.objectContaining({ method: "POST", credentials: "same-origin", cache: "no-store" }),
    );
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBe(JSON.stringify({ email: "p@example.test", password: "pw" }));
  });

  it("registers through the matching endpoint", async () => {
    fetchMock.mockResolvedValue(json({ accessToken: "a", expiresIn: 900, user: USER }, { status: 201 }));

    await authenticate("register", "p@example.test", "pw");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/auth/register");
  });

  it("works out when the token expires by this browser's clock", async () => {
    fetchMock.mockResolvedValue(json({ accessToken: "a", expiresIn: 900, user: USER }));

    const session = await authenticate("login", "p@example.test", "pw", 1_000);

    expect(session).toEqual({ accessToken: "a", expiresAt: 1_000 + 900_000, user: USER });
  });

  it("raises what the server said, with its status", async () => {
    fetchMock.mockResolvedValue(json({ detail: "Invalid email or password." }, { status: 401 }));

    await expect(authenticate("login", "p@example.test", "x")).rejects.toMatchObject({
      name: "AuthError",
      message: "Invalid email or password.",
      status: 401,
    });
  });

  it("carries how long to wait when the server said so", async () => {
    fetchMock.mockResolvedValue(
      json({ detail: "Too many." }, { status: 429, headers: { "Retry-After": "900", "Content-Type": "application/json" } }),
    );

    await expect(authenticate("login", "p@example.test", "x")).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 900,
    });
  });

  it("gives a general message when the answer was not ours", async () => {
    fetchMock.mockResolvedValue(new Response("<html>bad gateway</html>", { status: 502 }));

    await expect(authenticate("login", "p@example.test", "x")).rejects.toMatchObject({
      message: "Something went wrong. Try again.",
      status: 502,
    });
  });

  it("says so when the site itself could not be reached", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const failure = await authenticate("login", "p@example.test", "x").catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(AuthError);
    expect(failure).toMatchObject({ status: 0, message: expect.stringContaining("Could not reach Trackline") });
  });
});

describe("renewing from the browser", () => {
  it("is null, not an error, for a visitor with no session", async () => {
    fetchMock.mockResolvedValue(json({ detail: "There is no session to refresh." }, { status: 401 }));

    await expect(renew()).resolves.toBeNull();
  });

  it("gives the new session when there is one", async () => {
    fetchMock.mockResolvedValue(json({ accessToken: "fresh", expiresIn: 900, user: USER }));

    await expect(renew(5_000)).resolves.toEqual({ accessToken: "fresh", expiresAt: 5_000 + 900_000, user: USER });
  });

  it("throws, rather than answering null, when the answer could not be had, since that is not signing out", async () => {
    fetchMock.mockResolvedValue(json({ detail: "The Trackline API could not be reached." }, { status: 502 }));

    await expect(renew()).rejects.toMatchObject({ status: 502 });
  });

  it("throws when the site could not be reached at all", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(renew()).rejects.toBeInstanceOf(AuthError);
  });
});

describe("signing out from the browser", () => {
  it("posts to the sign-out endpoint", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await endSession();

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/auth/logout");
  });

  it("never throws, since the page forgets the person whether or not the server heard", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    await expect(endSession()).resolves.toBeUndefined();
  });
});
