"use client";

import Link from "next/link";

import { useAuth } from "@/components/auth/AuthProvider";
import { useFavourites } from "@/components/favourites/FavouritesProvider";
import { cn } from "@/lib/cn";
import type { FavouriteKind } from "@/lib/favourites/api";

type FavouriteButtonProps = {
  kind: FavouriteKind;
  id: string;
  /** What is being saved, in words, so a screen reader hears which one: "Route 501" or "Union Station". */
  name: string;
  /** Where to come back to after signing in. Shown as a link for a visitor; without it a visitor sees nothing. */
  signInReturnTo?: string;
  /** Icon only, for a row in a list. The name is still the button's accessible name. */
  compact?: boolean;
  className?: string;
};

/**
 * Saves or removes a route or a stop.
 *
 * Shape tells saved from not saved as well as fill: a filled star and an outline one, with words, so the state is never
 * only a colour. For a visitor with no account there is nothing to press, so it says what an account is for, as a link,
 * where there is room for that; in a crowded list it says nothing, since a sign-in prompt on every row is noise.
 */
export function FavouriteButton({ kind, id, name, signInReturnTo, compact = false, className }: FavouriteButtonProps) {
  const { state } = useAuth();
  const favourites = useFavourites();

  if (state.status === "loading") {
    return null;
  }

  if (state.status === "anonymous") {
    return signInReturnTo === undefined ? null : (
      <Link
        href={{ pathname: "/login", query: { next: signInReturnTo } }}
        className={cn("rounded-md text-sm text-accent underline-offset-2 hover:underline", className)}
      >
        Sign in to save {kind === "routes" ? "this route" : "this stop"}
      </Link>
    );
  }

  const saved = favourites.isSaved(kind, id);
  const pending = favourites.isPending(kind, id);
  const noun = kind === "routes" ? "route" : "stop";
  const action = saved ? `Remove ${name} from saved ${noun}s` : `Save ${name}`;

  return (
    <button
      type="button"
      aria-pressed={saved}
      // Always named in full, so a screen reader hears which one. The visible word "Save" is inside the name, as it must be.
      aria-label={action}
      aria-busy={pending}
      title={action}
      onClick={() => void favourites.toggle(kind, id)}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-md text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent",
        compact ? "size-7 justify-center hover:bg-surface-inset" : "px-2 py-1 hover:bg-surface-inset",
        saved ? "text-ink" : "text-ink-muted",
        pending && "opacity-60",
        className,
      )}
    >
      <svg viewBox="0 0 24 24" aria-hidden className="size-4 shrink-0" data-saved={saved}>
        <path
          d="M12 2.5l2.9 6.1 6.6.9-4.8 4.6 1.2 6.6L12 17.5l-5.9 3.2 1.2-6.6L2.5 9.5l6.6-.9L12 2.5z"
          fill={saved ? "currentColor" : "none"}
          stroke="currentColor"
          strokeWidth={1.8}
          strokeLinejoin="round"
        />
      </svg>
      {compact ? null : <span>{saved ? "Saved" : "Save"}</span>}
    </button>
  );
}
