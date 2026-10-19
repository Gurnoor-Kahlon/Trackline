import { vi } from "vitest";

import type { AuthState, Session } from "@/lib/auth/types";

export const USER = { id: "user-1", email: "person@example.test", createdAt: "2026-10-20T12:00:00Z" };

export function session(overrides: Partial<Session> = {}): Session {
  return { accessToken: "token-1", expiresAt: Date.now() + 900_000, user: USER, ...overrides };
}

export const LOADING: AuthState = { status: "loading" };
export const ANONYMOUS: AuthState = { status: "anonymous" };

export function authenticated(overrides: Partial<Session> = {}): AuthState {
  return { status: "authenticated", session: session(overrides) };
}

/** What useAuth answers in a test, with the calls recorded. */
export function authValue(state: AuthState) {
  return {
    state,
    signIn: vi.fn<(mode: "login" | "register", email: string, password: string) => Promise<void>>(),
    signOut: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
    getAccessToken: vi.fn<(renewFirst?: boolean) => Promise<string | null>>().mockResolvedValue("token-1"),
  };
}
