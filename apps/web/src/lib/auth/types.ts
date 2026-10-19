/**
 * Who is signed in, and the credential for calling the API on their behalf.
 *
 * The access token lives in memory only: it is gone on a reload and is renewed from the refresh token, which is held in a
 * cookie JavaScript cannot read. Nothing here is ever written to localStorage, where a script injected into the page
 * could take it.
 */
export type AuthUser = {
  id: string;
  email: string;
  createdAt: string;
};

/** What the web application's own sign-in endpoints answer. The refresh token is not in it; it is in a cookie. */
export type SessionResponse = {
  accessToken: string;
  /** Seconds the access token is good for. */
  expiresIn: number;
  user: AuthUser;
};

export type Session = {
  accessToken: string;
  /** When the access token stops being accepted, in milliseconds since the epoch, by this browser's clock. */
  expiresAt: number;
  user: AuthUser;
};

export type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; session: Session };

/** A sign-in that did not work, with what the person should be told. */
export class AuthError extends Error {
  readonly status: number;
  /** Seconds to wait before trying again, when the server said so. */
  readonly retryAfterSeconds: number | undefined;

  constructor(message: string, status: number, retryAfterSeconds?: number) {
    super(message);
    this.name = "AuthError";
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}
