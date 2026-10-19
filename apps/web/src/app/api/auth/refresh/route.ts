import {
  REFRESH_COOKIE,
  callBackend,
  clearRefreshCookie,
  passError,
  problem,
  readCookie,
  refuseCrossSite,
  relay,
  unreachable,
} from "@/lib/auth/server";

/**
 * Exchanges the refresh cookie for a new access token, and a new cookie.
 *
 * A refusal from the API means the sign-in is over, so the cookie is cleared with it and the page does not keep
 * presenting a token that cannot work. An API that cannot be reached, or that fails, says nothing about the sign-in,
 * so the cookie is left alone and the next try may succeed.
 */
export async function POST(request: Request) {
  const refused = refuseCrossSite(request);
  if (refused) {
    return refused;
  }
  const token = readCookie(request, REFRESH_COOKIE);
  if (token === null || token === "") {
    // Nobody is signed in. There is nothing to ask the API.
    return problem(401, "Not signed in", "There is no session to refresh.");
  }

  const backend = await callBackend("/api/v1/auth/refresh", { refreshToken: token });
  if (backend === null) {
    return unreachable();
  }
  if (backend.status === 401) {
    const response = await passError(backend);
    clearRefreshCookie(response);
    return response;
  }
  return relay(backend, 200);
}
