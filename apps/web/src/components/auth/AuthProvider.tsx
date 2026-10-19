"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { authenticate, endSession, renew } from "@/lib/auth/client";
import type { AuthState, Session } from "@/lib/auth/types";

/**
 * How long before an access token expires it is renewed, so a request is not sent with one that is about to stop working.
 */
const RENEW_AHEAD_MS = 60_000;

/** A token with less than this left is renewed before it is used, not after it has failed. */
const USABLE_FOR_MS = 30_000;

/** Never renew more often than this, whatever the clock says, so a bad expiry cannot make a loop. */
const MIN_RENEW_DELAY_MS = 5_000;

type AuthContextValue = {
  state: AuthState;
  /** Signs in or registers. Rejects with an AuthError whose message is fit to show. */
  signIn: (mode: "login" | "register", email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /**
   * A token good for at least half a minute, renewing first if need be. Null when nobody is signed in.
   *
   * @param renewFirst renew even if the token looks good, for a call the API has just refused
   */
  getAccessToken: (renewFirst?: boolean) => Promise<string | null>;
};

/**
 * Without a provider nobody is signed in. Every page works without an account, so a component that asks who is signed
 * in must get an answer, and the honest one is "nobody" when nothing has said otherwise.
 */
const SIGNED_OUT: AuthContextValue = {
  state: { status: "anonymous" },
  signIn: () => Promise.reject(new Error("Sign-in is not available here.")),
  signOut: () => Promise.resolve(),
  getAccessToken: () => Promise.resolve(null),
};

const AuthContext = createContext<AuthContextValue>(SIGNED_OUT);

export function useAuth(): AuthContextValue {
  return useContext(AuthContext);
}

/**
 * Who is signed in, for the whole application.
 *
 * On load it asks the web application to renew the session from its cookie: if there is one, the person is signed in
 * without doing anything, and if not they are an ordinary visitor. The access token is held here in memory and nowhere
 * else, and is renewed shortly before it expires.
 *
 * Renewing is one request at a time however many callers want it. A refresh token can be used only once, so two
 * requests made together with the same one would be a use and a reuse, and the second would end the session.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading" });
  const session = useRef<Session | null>(null);
  const inFlight = useRef<Promise<Session | null> | null>(null);

  const apply = useCallback((next: Session | null) => {
    session.current = next;
    setState(next === null ? { status: "anonymous" } : { status: "authenticated", session: next });
  }, []);

  const renewOnce = useCallback((): Promise<Session | null> => {
    if (inFlight.current !== null) {
      return inFlight.current;
    }
    const attempt = renew()
      .then(
        (renewed) => {
          apply(renewed);
          return renewed;
        },
        () => {
          // The answer could not be had, which says nothing about whether the session exists. Someone already signed
          // in stays signed in and is retried at the next use; a visitor loading the page is shown as one.
          if (session.current === null) {
            apply(null);
          }
          return session.current;
        },
      )
      .finally(() => {
        inFlight.current = null;
      });
    inFlight.current = attempt;
    return attempt;
  }, [apply]);

  useEffect(() => {
    void renewOnce();
  }, [renewOnce]);

  const expiresAt = state.status === "authenticated" ? state.session.expiresAt : null;
  useEffect(() => {
    if (expiresAt === null) {
      return;
    }
    const delay = Math.max(MIN_RENEW_DELAY_MS, expiresAt - Date.now() - RENEW_AHEAD_MS);
    const timer = setTimeout(() => void renewOnce(), delay);
    return () => clearTimeout(timer);
  }, [expiresAt, renewOnce]);

  const signIn = useCallback(
    async (mode: "login" | "register", email: string, password: string) => {
      apply(await authenticate(mode, email, password));
    },
    [apply],
  );

  const signOut = useCallback(async () => {
    await endSession();
    apply(null);
  }, [apply]);

  const getAccessToken = useCallback(
    async (renewFirst = false): Promise<string | null> => {
      const current = session.current;
      if (current === null) {
        // Still working out whether anyone is signed in: wait for that, but start nothing.
        return inFlight.current === null ? null : ((await inFlight.current)?.accessToken ?? null);
      }
      if (!renewFirst && current.expiresAt - Date.now() > USABLE_FOR_MS) {
        return current.accessToken;
      }
      return (await renewOnce())?.accessToken ?? null;
    },
    [renewOnce],
  );

  const value = useMemo(
    () => ({ state, signIn, signOut, getAccessToken }),
    [state, signIn, signOut, getAccessToken],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
