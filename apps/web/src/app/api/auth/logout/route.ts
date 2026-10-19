import { NextResponse } from "next/server";

import {
  REFRESH_COOKIE,
  callBackend,
  clearRefreshCookie,
  readCookie,
  refuseCrossSite,
} from "@/lib/auth/server";

/**
 * Signs out: ends the session at the API and removes the cookie.
 *
 * The cookie is removed whatever the API said. Someone who asked to sign out and still finds themselves signed in
 * because the API was briefly unreachable has been given the opposite of what they asked for.
 */
export async function POST(request: Request) {
  const refused = refuseCrossSite(request);
  if (refused) {
    return refused;
  }
  const token = readCookie(request, REFRESH_COOKIE);
  if (token !== null && token !== "") {
    await callBackend("/api/v1/auth/logout", { refreshToken: token });
  }
  const response = new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  clearRefreshCookie(response);
  return response;
}
