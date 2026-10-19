"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useState, type FormEvent } from "react";

import { useAuth } from "@/components/auth/AuthProvider";
import { Button } from "@/components/ui/Button";
import { AuthError } from "@/lib/auth/types";

type AuthFormProps = {
  mode: "login" | "register";
  /** Where to go once signed in. Already checked to be a path on this site. */
  next: string;
};

const COPY = {
  login: {
    heading: "Sign in",
    submit: "Sign in",
    pending: "Signing in…",
    switchPrompt: "No account yet?",
    switchLabel: "Create one",
    switchHref: "/register",
    passwordAutocomplete: "current-password",
  },
  register: {
    heading: "Create an account",
    submit: "Create account",
    pending: "Creating account…",
    switchPrompt: "Already have an account?",
    switchLabel: "Sign in",
    switchHref: "/login",
    passwordAutocomplete: "new-password",
  },
} as const;

/** What to tell someone who has been shut out for a while, in minutes, since seconds are not how anyone waits. */
function describeWait(seconds: number): string {
  const minutes = Math.max(1, Math.ceil(seconds / 60));
  return `Too many failed attempts. Try again in about ${minutes} ${minutes === 1 ? "minute" : "minutes"}.`;
}

/**
 * The sign-in and registration form.
 *
 * Every page works without an account; this exists only to save routes and stops. Errors are read out as they appear and
 * keep what was typed, a wrong password does not clear the address, and the server's own words are shown, since it is
 * the one that knows the rule that was broken. A person already signed in who lands here is sent on, not shown a form.
 */
export function AuthForm({ mode, next }: AuthFormProps) {
  const copy = COPY[mode];
  const router = useRouter();
  const { state, signIn } = useAuth();
  const emailId = useId();
  const passwordId = useId();
  const hintId = useId();
  const errorId = useId();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (state.status === "authenticated") {
      router.replace(next as never);
    }
  }, [state.status, next, router]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      await signIn(mode, email, password);
      // Leaving the page is the confirmation.
      router.push(next as never);
    } catch (cause) {
      setError(
        cause instanceof AuthError
          ? cause.status === 429 && cause.retryAfterSeconds !== undefined
            ? describeWait(cause.retryAfterSeconds)
            : cause.message
          : "Something went wrong. Try again.",
      );
      // The address is kept for another go; the password is not left sitting in the form after a refusal.
      setPassword("");
      setPending(false);
    }
  }

  return (
    <section aria-labelledby="auth-heading" className="mx-auto w-full max-w-sm space-y-5">
      <div className="space-y-1">
        <h1 id="auth-heading" className="text-2xl font-bold tracking-tight text-ink">
          {copy.heading}
        </h1>
        <p className="text-sm text-ink-muted">
          An account only lets you save routes and stops. Everything else on Trackline works without one.
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <div className="space-y-1">
          <label htmlFor={emailId} className="text-sm font-medium text-ink">
            Email address
          </label>
          <input
            id={emailId}
            name="email"
            type="email"
            inputMode="email"
            autoComplete={mode === "login" ? "username" : "email"}
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            aria-describedby={error === null ? undefined : errorId}
            className="w-full rounded-md border border-border-strong bg-surface px-3 py-2 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          />
        </div>

        <div className="space-y-1">
          <label htmlFor={passwordId} className="text-sm font-medium text-ink">
            Password
          </label>
          <input
            id={passwordId}
            name="password"
            type="password"
            autoComplete={copy.passwordAutocomplete}
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            aria-describedby={[mode === "register" ? hintId : null, error === null ? null : errorId]
              .filter((id): id is string => id !== null)
              .join(" ") || undefined}
            className="w-full rounded-md border border-border-strong bg-surface px-3 py-2 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
          />
          {mode === "register" ? (
            <p id={hintId} className="text-xs text-ink-muted">
              At least 10 characters, and no more than 72 bytes. A few ordinary words together are stronger than a short
              password with symbols.
            </p>
          ) : null}
        </div>

        {error === null ? null : (
          <p id={errorId} role="alert" className="text-sm font-medium text-status-major">
            {error}
          </p>
        )}

        <Button type="submit" variant="primary" disabled={pending} className="w-full">
          {pending ? copy.pending : copy.submit}
        </Button>
      </form>

      <p className="text-sm text-ink-muted">
        {copy.switchPrompt}{" "}
        <Link
          href={{ pathname: copy.switchHref, ...(next === "/" ? {} : { query: { next } }) }}
          className="font-medium text-accent underline-offset-2 hover:underline"
        >
          {copy.switchLabel}
        </Link>
      </p>
    </section>
  );
}
