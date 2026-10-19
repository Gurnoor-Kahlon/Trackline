"use client";

import Link from "next/link";

import { useAuth } from "@/components/auth/AuthProvider";

/**
 * Who is signed in, and how to sign in or out, in the header.
 *
 * Nothing is drawn while the page is finding out, so a person who is signed in does not see "Sign in" flash up and
 * vanish on every load. It reserves no space either, which is the price: the header does not shift only because the
 * menu is a small, right-aligned element beside others that do not move.
 */
export function AccountMenu() {
  const { state, signOut } = useAuth();

  if (state.status === "loading") {
    return null;
  }

  if (state.status === "anonymous") {
    return (
      <Link
        href="/login"
        className="rounded-md px-2.5 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-inset hover:text-ink"
      >
        Sign in
      </Link>
    );
  }

  return (
    <div className="flex items-center gap-2">
      <span className="max-w-40 truncate text-xs text-ink-muted" title={state.session.user.email}>
        <span className="sr-only">Signed in as </span>
        {state.session.user.email}
      </span>
      <button
        type="button"
        onClick={() => void signOut()}
        className="rounded-md px-2.5 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-inset hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
      >
        Sign out
      </button>
    </div>
  );
}
