import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/lib/api/client";
import { fetchFavourites, setFavourite } from "@/lib/favourites/api";

const fetchMock = vi.fn<typeof fetch>();

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

/** A token source that hands out these in turn, and records whether it was asked to renew. */
function tokens(...values: (string | null)[]) {
  const queue = [...values];
  return vi.fn<(renewFirst?: boolean) => Promise<string | null>>(() => Promise.resolve(queue.shift() ?? null));
}

describe("reading the saved routes and stops", () => {
  it("asks the API with the access token and nothing that names a person", async () => {
    fetchMock.mockResolvedValue(json({ routes: [], stops: [] }));

    await fetchFavourites(tokens("tok-1"));

    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:8080/api/v1/favourites",
      expect.objectContaining({
        method: "GET",
        cache: "no-store",
        headers: { Accept: "application/json", Authorization: "Bearer tok-1" },
      }),
    );
  });

  it("returns what the API said", async () => {
    const body = { routes: [{ routeId: "501", savedAt: "2026-10-20T12:00:00Z", route: null }], stops: [] };
    fetchMock.mockResolvedValue(json(body));

    await expect(fetchFavourites(tokens("tok-1"))).resolves.toEqual(body);
  });

  it("does not call the API at all when nobody is signed in", async () => {
    await expect(fetchFavourites(tokens(null))).rejects.toMatchObject({ status: 401 });

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("tries once more with a renewed token when the first is refused, since it may have lapsed in flight", async () => {
    fetchMock
      .mockResolvedValueOnce(json({ title: "Authentication required" }, { status: 401 }))
      .mockResolvedValueOnce(json({ routes: [], stops: [] }));
    const source = tokens("old", "renewed");

    await fetchFavourites(source);

    expect(source).toHaveBeenNthCalledWith(1);
    expect(source).toHaveBeenNthCalledWith(2, true);
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({ Authorization: "Bearer renewed" });
  });

  it("gives up after the second refusal rather than looping", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(json({ title: "Authentication required", detail: "A valid access token is required." }, { status: 401 })));

    await expect(fetchFavourites(tokens("old", "renewed"))).rejects.toMatchObject({
      status: 401,
      message: "A valid access token is required.",
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("says the session has ended when renewing finds nobody signed in", async () => {
    fetchMock.mockResolvedValue(json({ title: "x" }, { status: 401 }));

    await expect(fetchFavourites(tokens("old", null))).rejects.toMatchObject({
      status: 401,
      message: "Your session has ended. Sign in again.",
    });
  });

  it("raises the API's own words for any other refusal", async () => {
    fetchMock.mockResolvedValue(json({ title: "Invalid request", detail: "nope", errorId: "e-1" }, { status: 400 }));

    await expect(fetchFavourites(tokens("tok"))).rejects.toMatchObject({ status: 400, message: "nope", errorId: "e-1" });
  });

  it("says what it can when the answer was not ours", async () => {
    fetchMock.mockResolvedValue(new Response("<html>", { status: 503 }));

    await expect(fetchFavourites(tokens("tok"))).rejects.toMatchObject({ message: "The API returned 503." });
  });

  it("says so when the API could not be reached", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const failure = await fetchFavourites(tokens("tok")).catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({ status: 0, message: "Could not reach the Trackline API." });
  });
});

describe("saving and removing", () => {
  it("saves with PUT and removes with DELETE", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));

    await setFavourite(tokens("tok", "tok"), "routes", "501", true);
    await setFavourite(tokens("tok"), "stops", "3718", false);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("http://localhost:8080/api/v1/favourites/routes/501");
    expect(fetchMock.mock.calls[0]?.[1]?.method).toBe("PUT");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("http://localhost:8080/api/v1/favourites/stops/3718");
    expect(fetchMock.mock.calls[1]?.[1]?.method).toBe("DELETE");
  });

  it("encodes the id, since it comes from data", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await setFavourite(tokens("tok"), "routes", "a b/c?d", true);

    expect(fetchMock.mock.calls[0]?.[0]).toBe("http://localhost:8080/api/v1/favourites/routes/a%20b%2Fc%3Fd");
  });

  it("raises the API's refusal, such as a route it does not know", async () => {
    fetchMock.mockResolvedValue(json({ title: "Resource not found", detail: "Route 'x' was not found" }, { status: 404 }));

    await expect(setFavourite(tokens("tok"), "routes", "x", true)).rejects.toMatchObject({
      status: 404,
      message: "Route 'x' was not found",
    });
  });

  it("is told the limit when it is reached", async () => {
    fetchMock.mockResolvedValue(
      json({ title: "Invalid request", detail: "You can save at most 100 routes. Remove one first." }, { status: 400 }),
    );

    await expect(setFavourite(tokens("tok"), "routes", "7", true)).rejects.toMatchObject({
      message: expect.stringContaining("at most 100 routes"),
    });
  });
});
