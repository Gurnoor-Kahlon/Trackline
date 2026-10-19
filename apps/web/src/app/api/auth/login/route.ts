import { callBackend, refuseCrossSite, relay, unreachable } from "@/lib/auth/server";

/** Signs in, keeping the refresh token out of the page's reach. */
export async function POST(request: Request) {
  const refused = refuseCrossSite(request);
  if (refused) {
    return refused;
  }
  const body = await readCredentials(request);
  const backend = await callBackend("/api/v1/auth/login", body);
  return backend === null ? unreachable() : relay(backend, 200);
}

async function readCredentials(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    return {};
  }
}
