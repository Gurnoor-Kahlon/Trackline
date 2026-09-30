import Link from "next/link";

import { SearchTrigger } from "@/components/shell/SearchTrigger";
import { ThemeToggle } from "@/components/shell/ThemeToggle";

/**
 * Only routes that exist are listed. Map and alerts join this list in the
 * milestones that build them, rather than appearing now as links to nothing.
 */
const NAV_ITEMS = [{ href: "/", label: "Home" }] as const;

/**
 * The application header: wordmark, primary navigation, search and theme.
 *
 * A real <nav> landmark with a list, so a screen reader can enumerate and skip
 * the navigation. The wordmark is a link home rather than a heading, because the
 * page's own heading should describe the page.
 */
export function AppHeader() {
  return (
    <header className="sticky top-0 z-10 border-b border-border bg-surface/95 backdrop-blur-sm">
      <div className="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:gap-6">
        <div className="flex items-center justify-between gap-4 sm:justify-start">
          <Link href="/" className="group flex items-baseline gap-2 rounded-sm">
            <span className="text-lg font-bold tracking-[0.18em] text-ink">TRACKLINE</span>
            <span className="hidden text-xs text-ink-subtle sm:inline">See Toronto move.</span>
          </Link>
          <div className="sm:hidden">
            <ThemeToggle />
          </div>
        </div>

        <nav aria-label="Primary" className="sm:ml-2">
          <ul className="flex items-center gap-1">
            {NAV_ITEMS.map((item) => (
              <li key={item.href}>
                <Link
                  href={item.href}
                  className="rounded-md px-2.5 py-1.5 text-sm font-medium text-ink-muted hover:bg-surface-inset hover:text-ink"
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        <div className="flex flex-1 items-center justify-end gap-2">
          <SearchTrigger />
          <div className="hidden sm:block">
            <ThemeToggle />
          </div>
        </div>
      </div>
    </header>
  );
}
