/**
 * The theme store.
 *
 * The chosen theme lives on the document element, applied before first paint by a
 * blocking script so there is no flash of the wrong theme. That makes it external
 * mutable state as far as React is concerned, so it is read through
 * `useSyncExternalStore` rather than copied into component state inside an effect
 * — which would cause a cascading render on every mount.
 */

export type Theme = "light" | "dark";

const STORAGE_KEY = "trackline-theme";

const listeners = new Set<() => void>();

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The theme currently applied, falling back to the operating system preference. */
export function getTheme(): Theme {
  const applied = document.documentElement.dataset.theme;
  if (applied === "light" || applied === "dark") {
    return applied;
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

/**
 * On the server there is no document and no OS preference, and guessing would
 * produce markup that contradicts what the blocking script is about to apply.
 */
export function getServerTheme(): null {
  return null;
}

export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // A rejected write only costs the preference on the next visit, so the theme
    // still changes for this one.
  }
  for (const listener of listeners) {
    listener();
  }
}
