import { AuthError, type Session, type SessionResponse } from "@/lib/auth/types";

/**
 * The browser's calls to this site's own sign-in endpoints. They go to the web application, not the API, so the
 * refresh token travels in a cookie the page cannot read.
 */

function toSession(response: SessionResponse, now: number): Session {
  return {
    accessToken: response.accessToken,
    // By this browser's clock, from the seconds the server said. A laptop with the wrong time still renews on schedule.
    expiresAt: now + response.expiresIn * 1000,
    user: response.user,
  };
}

async function failure(response: Response): Promise<AuthError> {
  let detail = "Something went wrong. Try again.";
  try {
    const parsed = (await response.json()) as { detail?: string };
    if (typeof parsed.detail === "string" && parsed.detail !== "") {
      detail = parsed.detail;
    }
  } catch {
    // Not JSON: something other than our own endpoint answered.
  }
  const retryAfter = Number(response.headers.get("retry-after"));
  return new AuthError(detail, response.status, Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined);
}

async function post(path: string, body?: unknown): Promise<Response> {
  try {
    return await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      cache: "no-store",
      credentials: "same-origin",
    });
  } catch {
    throw new AuthError("Could not reach Trackline. Check your connection and try again.", 0);
  }
}

/** Signs in or registers. Throws an AuthError carrying what to tell the person. */
export async function authenticate(
  mode: "login" | "register",
  email: string,
  password: string,
  now: number = Date.now(),
): Promise<Session> {
  const response = await post(`/api/auth/${mode}`, { email, password });
  if (!response.ok) {
    throw await failure(response);
  }
  return toSession((await response.json()) as SessionResponse, now);
}

/**
 * Renews the session from the refresh cookie.
 *
 * Returns null when there is no session to renew, which is the ordinary state of a visitor who is not signed in, and
 * not an error. Throws only when the answer could not be had (the server was unreachable or failed), which says
 * nothing about whether a session exists, so the caller must not treat it as signing out.
 */
export async function renew(now: number = Date.now()): Promise<Session | null> {
  const response = await post("/api/auth/refresh");
  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw await failure(response);
  }
  return toSession((await response.json()) as SessionResponse, now);
}

/** Ends the session. Never throws: the page is signed out here whether or not the server heard. */
export async function endSession(): Promise<void> {
  try {
    await post("/api/auth/logout");
  } catch {
    // The cookie may stay until its expiry, and the page forgets the person regardless.
  }
}
