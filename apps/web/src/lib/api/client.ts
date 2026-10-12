import type { ApiEnvelope, ApiProblem } from "@/lib/api/types";

/**
 * The single place the frontend talks to the API.
 *
 * Centralised so that the envelope is unwrapped once, failures become one error
 * type, and no component ever calls `fetch` with a hand-written URL. A scattered
 * fetch is how a base URL ends up hard-coded in a component and only discovered
 * after deployment.
 */

const DEFAULT_BASE_URL = "http://localhost:8080";

export function apiBaseUrl(): string {
  return process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_BASE_URL;
}

/**
 * A request that did not produce usable data.
 *
 * Carries the API's own `errorId` when there was one, so a user reporting a
 * problem can quote something that appears in the server logs.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly errorId: string | undefined;

  constructor(message: string, status: number, errorId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.errorId = errorId;
  }

  /** True when the API answered but said no, as opposed to being unreachable. */
  get isResponse(): boolean {
    return this.status > 0;
  }
}

type RequestOptions = {
  /** Seconds to cache in Next's data cache. Omit for no caching. */
  revalidateSeconds?: number;
  signal?: AbortSignal;
};

/**
 * Performs a request and returns the whole envelope.
 *
 * The envelope is returned rather than just `data` because `meta` is not
 * decoration: it carries how old the data is and which tier served it, and a
 * caller that drops it will render stale data as though it were current.
 */
export async function fetchEnvelope<T>(path: string, options: RequestOptions = {}): Promise<ApiEnvelope<T>> {
  const url = `${apiBaseUrl()}${path}`;

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { Accept: "application/json" },
      ...(options.signal ? { signal: options.signal } : {}),
      ...(options.revalidateSeconds === undefined
        ? { cache: "no-store" as const }
        : { next: { revalidate: options.revalidateSeconds } }),
    });
  } catch (cause) {
    // Status 0 distinguishes "could not reach the API at all" from "the API
    // replied with an error", which are different things to tell a user.
    throw new ApiError(
      cause instanceof Error && cause.name === "AbortError"
        ? "The request was cancelled."
        : "Could not reach the Trackline API.",
      0,
    );
  }

  if (!response.ok) {
    throw await toApiError(response);
  }

  return (await response.json()) as ApiEnvelope<T>;
}

/** Performs a request and returns only the payload, for calls that ignore freshness. */
export async function fetchData<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const envelope = await fetchEnvelope<T>(path, options);
  return envelope.data;
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const problem = (await response.json()) as ApiProblem;
    return new ApiError(problem.detail ?? problem.title, response.status, problem.errorId);
  } catch {
    // A non-JSON error body means something in front of the API answered — a
    // proxy or a load balancer — and there is nothing useful to quote.
    return new ApiError(`The API returned ${response.status}.`, response.status);
  }
}
