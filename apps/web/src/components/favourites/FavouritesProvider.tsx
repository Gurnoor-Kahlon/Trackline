"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { useAuth } from "@/components/auth/AuthProvider";
import { ApiError } from "@/lib/api/client";
import { fetchFavourites, setFavourite, type FavouriteKind, type Favourites } from "@/lib/favourites/api";

type FavouritesContextValue = {
  /** "idle" while nobody is signed in. */
  status: "idle" | "loading" | "ready" | "error";
  /** What is saved, or null before it has loaded. */
  favourites: Favourites | null;
  /** Whether it is saved, counting a change that has been asked for and not yet confirmed. */
  isSaved: (kind: FavouriteKind, id: string) => boolean;
  /** Whether a change to it has been asked for and not yet confirmed. */
  isPending: (kind: FavouriteKind, id: string) => boolean;
  /** Saves it if it is not saved and removes it if it is. */
  toggle: (kind: FavouriteKind, id: string) => Promise<void>;
  /** The last thing that went wrong, in words, or null. */
  error: string | null;
};

const NONE: FavouritesContextValue = {
  status: "idle",
  favourites: null,
  isSaved: () => false,
  isPending: () => false,
  toggle: () => Promise.resolve(),
  error: null,
};

const FavouritesContext = createContext<FavouritesContextValue>(NONE);

/** What is saved. Without a provider, or without anyone signed in, nothing is, which is also what the page shows. */
export function useFavourites(): FavouritesContextValue {
  return useContext(FavouritesContext);
}

const keyOf = (kind: FavouriteKind, id: string) => `${kind}:${id}`;

function describeLoadFailure(cause: unknown): string {
  return cause instanceof ApiError ? cause.message : "Your saved routes and stops could not be loaded.";
}

/** What was last read for one person. Tagged with whose it is, so it can never be shown for anyone else. */
type Loaded =
  | { userId: string; status: "ready"; favourites: Favourites }
  | { userId: string; status: "error"; message: string };

/**
 * The signed-in person's saved routes and stops, for any component that wants to show or change them.
 *
 * Saving shows at once and is confirmed after: the star fills when it is pressed, and goes back, with a message, if the
 * API said no. The list is then read again, so what is shown after a change is what the API holds and not what this page
 * guessed.
 *
 * What was read is stored with the id of the person it belongs to, and used only while that person is the one signed in.
 * Signing out, or another person signing in, leaves it unreachable in the same render, with no step in between where
 * one person's saved routes could be on screen for the next.
 */
export function FavouritesProvider({ children }: { children: ReactNode }) {
  const { state, getAccessToken } = useAuth();
  const userId = state.status === "authenticated" ? state.session.user.id : null;

  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [changeError, setChangeError] = useState<string | null>(null);
  // Changes asked for and not yet confirmed: key to the state it will be in once they are.
  const [pending, setPending] = useState<ReadonlyMap<string, boolean>>(new Map());

  const load = useCallback(
    async (forUser: string, signal?: AbortSignal) => {
      try {
        const favourites = await fetchFavourites(getAccessToken, signal);
        setLoaded({ userId: forUser, status: "ready", favourites });
      } catch (cause) {
        if (signal?.aborted) {
          return;
        }
        setLoaded({
          userId: forUser,
          status: "error",
          message: describeLoadFailure(cause),
        });
      }
    },
    [getAccessToken],
  );

  useEffect(() => {
    if (userId === null) {
      return;
    }
    const controller = new AbortController();
    // Inline, and answered in a callback: this synchronises with the API, it does not set state while rendering.
    fetchFavourites(getAccessToken, controller.signal).then(
      (favourites) => {
        if (!controller.signal.aborted) {
          setLoaded({ userId, status: "ready", favourites });
        }
      },
      (cause: unknown) => {
        if (!controller.signal.aborted) {
          setLoaded({ userId, status: "error", message: describeLoadFailure(cause) });
        }
      },
    );
    return () => controller.abort();
  }, [userId, getAccessToken]);

  // Only what belongs to the person signed in now.
  const mine = userId !== null && loaded?.userId === userId ? loaded : null;
  const status: FavouritesContextValue["status"] =
    userId === null ? "idle" : mine === null ? "loading" : mine.status;
  const favourites = mine?.status === "ready" ? mine.favourites : null;
  const error = changeError ?? (mine?.status === "error" ? mine.message : null);

  const isSaved = useCallback(
    (kind: FavouriteKind, id: string): boolean => {
      const asked = pending.get(keyOf(kind, id));
      if (asked !== undefined) {
        return asked;
      }
      if (favourites === null) {
        return false;
      }
      return kind === "routes"
        ? favourites.routes.some((saved) => saved.routeId === id)
        : favourites.stops.some((saved) => saved.stopId === id);
    },
    [favourites, pending],
  );

  const isPending = useCallback(
    (kind: FavouriteKind, id: string): boolean => pending.has(keyOf(kind, id)),
    [pending],
  );

  const toggle = useCallback(
    async (kind: FavouriteKind, id: string) => {
      const key = keyOf(kind, id);
      if (userId === null || pending.has(key)) {
        // Nobody to save for, or a change to this one is already on its way and a second press would race it.
        return;
      }
      const wanted = !isSaved(kind, id);
      setPending((previous) => new Map(previous).set(key, wanted));
      setChangeError(null);
      try {
        await setFavourite(getAccessToken, kind, id, wanted);
        await load(userId);
      } catch (cause) {
        setChangeError(cause instanceof ApiError ? cause.message : "That could not be saved.");
      } finally {
        setPending((previous) => {
          const next = new Map(previous);
          next.delete(key);
          return next;
        });
      }
    },
    [userId, getAccessToken, isSaved, load, pending],
  );

  const value = useMemo(
    () => ({ status, favourites, isSaved, isPending, toggle, error }),
    [status, favourites, isSaved, isPending, toggle, error],
  );
  return <FavouritesContext.Provider value={value}>{children}</FavouritesContext.Provider>;
}
