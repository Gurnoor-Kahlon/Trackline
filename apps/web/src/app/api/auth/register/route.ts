import { callBackend, refuseCrossSite, relay, unreachable } from "@/lib/auth/server";

/** Registers an account and signs in, keeping the refresh token out of the page's reach. */
export async function POST(request: Request) {
  const refused = refuseCrossSite(request);
  if (refused) {
    return refused;
  }
  const body = await readCredentials(request);
  const backend = await callBackend("/api/v1/auth/register", body);
  return backend === null ? unreachable() : relay(backend, 201);
}

/** Whatever was sent is passed on for the API to judge: it is the one place the rules live. */
async function readCredentials(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    return {};
  }
}
