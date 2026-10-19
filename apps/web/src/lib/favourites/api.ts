import { ApiError, apiBaseUrl } from "@/lib/api/client";
import type { ApiProblem, RouteSummary, StopSummary } from "@/lib/api/types";

/**
 * The signed-in person's saved routes and stops, from the API.
 *
 * These calls carry an access token in the Authorization header, and nothing else says whose list it is: there is no
 * user id anywhere in a request.
 */

export type FavouriteRoute = {
  routeId: string;
  savedAt: string;
  /** What the schedule calls it, or null if the current schedule no longer lists it. */
  route: RouteSummary | null;
};

export type FavouriteStop = {
  stopId: string;
  savedAt: string;
  stop: StopSummary | null;
};

export type Favourites = {
  routes: FavouriteRoute[];
  stops: FavouriteStop[];
};

export type FavouriteKind = "routes" | "stops";

/** Supplies a token, renewing first when asked. Null means nobody is signed in. */
export type TokenSource = (renewFirst?: boolean) => Promise<string | null>;

async function send(path: string, method: "GET" | "PUT" | "DELETE", token: string, signal?: AbortSignal): Promise<Response> {
  try {
    return await fetch(`${apiBaseUrl()}${path}`, {
      method,
      headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
      cache: "no-store",
      ...(signal ? { signal } : {}),
    });
  } catch (cause) {
    throw new ApiError(
      cause instanceof Error && cause.name === "AbortError"
        ? "The request was cancelled."
        : "Could not reach the Trackline API.",
      0,
    );
  }
}

/**
 * Calls the API with a token, and once more with a freshly renewed one if the API says the first was no good.
 *
 * A token can lapse between being checked here and arriving there, so one refusal is not yet "signed out". Two are.
 */
async function authorised(
  tokens: TokenSource,
  path: string,
  method: "GET" | "PUT" | "DELETE",
  signal?: AbortSignal,
): Promise<Response> {
  const first = await tokens();
  if (first === null) {
    throw new ApiError("Sign in to save routes and stops.", 401);
  }
  const response = await send(path, method, first, signal);
  if (response.status !== 401) {
    return response;
  }
  const second = await tokens(true);
  if (second === null) {
    throw new ApiError("Your session has ended. Sign in again.", 401);
  }
  return send(path, method, second, signal);
}

async function check(response: Response): Promise<void> {
  if (response.ok) {
    return;
  }
  try {
    const problem = (await response.json()) as ApiProblem;
    throw new ApiError(problem.detail ?? problem.title, response.status, problem.errorId);
  } catch (cause) {
    if (cause instanceof ApiError) {
      throw cause;
    }
    throw new ApiError(`The API returned ${response.status}.`, response.status);
  }
}

export async function fetchFavourites(tokens: TokenSource, signal?: AbortSignal): Promise<Favourites> {
  const response = await authorised(tokens, "/api/v1/favourites", "GET", signal);
  await check(response);
  return (await response.json()) as Favourites;
}

export async function setFavourite(
  tokens: TokenSource,
  kind: FavouriteKind,
  id: string,
  saved: boolean,
): Promise<void> {
  const response = await authorised(
    tokens,
    `/api/v1/favourites/${kind}/${encodeURIComponent(id)}`,
    saved ? "PUT" : "DELETE",
  );
  await check(response);
}
