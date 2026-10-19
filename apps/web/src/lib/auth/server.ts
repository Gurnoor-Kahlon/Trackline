import { NextResponse } from "next/server";

import { apiBaseUrl } from "@/lib/api/client";
import type { SessionResponse } from "@/lib/auth/types";

/**
 * What the web application's sign-in endpoints share.
 *
 * The browser talks to these and not to the API for signing in, so that the refresh token, the one credential that lasts
 * weeks, can live in a cookie the page's JavaScript cannot read. The API's own tokens come back in a JSON body, which a
 * browser script can read, and keeping that token in a script-readable place is what a stolen session usually comes from.
 * Here the refresh token is taken out of the body and put in an HttpOnly cookie, and only the short-lived access token
 * is handed on.
 */

export const REFRESH_COOKIE = "trackline_refresh";

/** Only these endpoints ever see the cookie. */
export const COOKIE_PATH = "/api/auth";

/** What the API answers to a sign-in, register or refresh. */
type BackendTokens = {
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  user: SessionResponse["user"];
};

/**
 * Refuses a request that a page on another site could have caused.
 *
 * The cookie is sent with any request to this site, including one a different site's page makes on the person's behalf,
 * so a POST that changes the session must have come from here. Browsers add an Origin header to every cross-site POST,
 * and it cannot be set by the page, so a mismatch is a refusal. SameSite=Lax on the cookie is a second layer: it is not
 * sent with a cross-site POST at all. A request with no Origin did not come from a browser page, and there is no
 * ambient cookie for it to abuse.
 */
export function refuseCrossSite(request: Request): NextResponse | null {
  const origin = request.headers.get("origin");
  if (origin === null) {
    return null;
  }
  if (origin !== new URL(request.url).origin) {
    return problem(403, "Forbidden", "This request did not come from this site.");
  }
  return null;
}

/** An error in the shape the API uses, so the page handles one kind of error. */
export function problem(status: number, title: string, detail: string, headers?: HeadersInit): NextResponse {
  return NextResponse.json({ title, detail, status }, { status, ...(headers ? { headers } : {}) });
}

/** Reads one cookie from a request, since the handlers have no need of anything more. */
export function readCookie(request: Request, name: string): string | null {
  const header = request.headers.get("cookie");
  if (header === null) {
    return null;
  }
  for (const part of header.split(";")) {
    const separator = part.indexOf("=");
    if (separator > 0 && part.slice(0, separator).trim() === name) {
      try {
        return decodeURIComponent(part.slice(separator + 1).trim());
      } catch {
        // A cookie that is not valid encoding is not one we set.
        return null;
      }
    }
  }
  return null;
}

/** Calls the API. A failure to reach it is a null, which is not the same as the API saying no. */
export async function callBackend(path: string, body: unknown): Promise<Response | null> {
  try {
    return await fetch(`${apiBaseUrl()}${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return null;
  }
}

export function unreachable(): NextResponse {
  return problem(502, "Sign-in unavailable", "The Trackline API could not be reached.");
}

/**
 * Turns the API's answer into this site's: the access token in the body and the refresh token in the cookie.
 *
 * An error from the API is passed on as it is, with its status and its Retry-After, since a refusal is a refusal and
 * "try again in fifteen minutes" is only useful if it arrives.
 */
export async function relay(backend: Response, successStatus: number): Promise<NextResponse> {
  if (!backend.ok) {
    return passError(backend);
  }
  const tokens = (await backend.json()) as BackendTokens;
  const body: SessionResponse = { accessToken: tokens.accessToken, expiresIn: tokens.expiresIn, user: tokens.user };
  const response = NextResponse.json(body, { status: successStatus, headers: { "Cache-Control": "no-store" } });
  setRefreshCookie(response, tokens.refreshToken, tokens.refreshExpiresIn);
  return response;
}

/** The API's refusal, with the parts a page can use. */
export async function passError(backend: Response): Promise<NextResponse> {
  let detail = `The API returned ${backend.status}.`;
  let title = "Request failed";
  try {
    const parsed = (await backend.json()) as { title?: string; detail?: string };
    detail = parsed.detail ?? parsed.title ?? detail;
    title = parsed.title ?? title;
  } catch {
    // Something in front of the API answered with a page; the status is all there is to say.
  }
  const retryAfter = backend.headers.get("retry-after");
  return problem(backend.status, title, detail, retryAfter === null ? undefined : { "Retry-After": retryAfter });
}

export function setRefreshCookie(response: NextResponse, token: string, maxAgeSeconds: number): void {
  response.cookies.set({
    name: REFRESH_COOKIE,
    value: token,
    // Not readable from a page's script, whatever is injected into it.
    httpOnly: true,
    // Not sent with a request another site causes.
    sameSite: "lax",
    // Only to the endpoints that use it.
    path: COOKIE_PATH,
    // Http is fine for a developer's machine, which browsers treat as secure for localhost; anywhere else it is https.
    secure: process.env.NODE_ENV === "production",
    maxAge: maxAgeSeconds,
  });
}

export function clearRefreshCookie(response: NextResponse): void {
  response.cookies.set({
    name: REFRESH_COOKIE,
    value: "",
    httpOnly: true,
    sameSite: "lax",
    path: COOKIE_PATH,
    secure: process.env.NODE_ENV === "production",
    maxAge: 0,
  });
}
