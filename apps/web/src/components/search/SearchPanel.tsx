"use client";

import Link from "next/link";
import { useEffect, useId, useState } from "react";

import { EmptyState } from "@/components/ui/EmptyState";
import { RouteBadge } from "@/components/ui/RouteBadge";
import { SkeletonLines } from "@/components/ui/Skeleton";
import { fetchStopDetail } from "@/lib/api/search";
import type { RouteSummary, SearchHit } from "@/lib/api/types";
import { useSearch } from "@/lib/search/useSearch";

type SearchPanelProps = {
  /** Called when a result has been followed, so the sheet holding the panel can close. */
  onNavigate: () => void;
};

/**
 * Search across routes and stops, by name or number.
 *
 * A route leads to the map with that route selected. A stop has no page of its own, so choosing one lists the routes that call there and
 * each of those leads to the map: the answer to "which route do I take from this stop" is the useful part of a stop, and the map is the
 * only place anything can be shown. Stations are left out of the list, because they are groupings Trackline derives and nothing opens one.
 */
export function SearchPanel({ onNavigate }: SearchPanelProps) {
  const [text, setText] = useState("");
  const search = useSearch(text);
  const inputId = useId();
  const statusId = useId();

  const hits = search.status === "ready" ? search.hits.filter((hit) => hit.kind !== "STATION") : [];
  const routes = hits.filter((hit) => hit.kind === "ROUTE");
  const stops = hits.filter((hit) => hit.kind === "STOP");

  return (
    <div className="flex flex-col gap-3">
      <div>
        <label htmlFor={inputId} className="text-sm font-medium text-ink">
          Search routes and stops
        </label>
        <input
          id={inputId}
          type="search"
          data-autofocus
          autoComplete="off"
          spellCheck={false}
          value={text}
          onChange={(event) => setText(event.target.value)}
          aria-describedby={statusId}
          placeholder="501, Queen, Kennedy"
          className="mt-1 w-full rounded-md border border-border bg-surface-raised px-3 py-2 text-sm text-ink placeholder:text-ink-subtle"
        />
      </div>

      {/* Announced politely as it changes, so a person using a screen reader hears that results arrived without being interrupted. */}
      <p id={statusId} role="status" className="text-xs text-ink-muted">
        {describe(search.status, search.status === "ready" ? hits.length : 0, text.trim())}
      </p>

      {search.status === "loading" ? <SkeletonLines lines={3} label="Searching" /> : null}

      {search.status === "error" ? (
        <p role="alert" className="rounded-md border border-border bg-surface-inset px-3 py-2 text-sm text-ink">
          {search.message}
        </p>
      ) : null}

      {search.status === "ready" && hits.length === 0 ? (
        <EmptyState
          title="No routes or stops match"
          description="Try a route number, or part of a street or stop name."
        />
      ) : null}

      {routes.length > 0 ? (
        <section aria-labelledby={`${inputId}-routes`}>
          <h3 id={`${inputId}-routes`} className="mb-1 text-xs font-semibold tracking-wide text-ink-muted uppercase">
            Routes
          </h3>
          <ul>
            {routes.map((hit) => (
              <li key={hit.id}>
                <Link
                  href={{ pathname: "/map", query: { route: hit.id } }}
                  onClick={onNavigate}
                  className="flex items-baseline gap-2 rounded-md px-2 py-1.5 text-sm text-ink hover:bg-surface-inset"
                >
                  <span className="tabular w-10 shrink-0 font-semibold">{hit.label}</span>
                  <span className="min-w-0 flex-1 truncate text-ink-muted">{hit.sublabel}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {stops.length > 0 ? (
        <section aria-labelledby={`${inputId}-stops`}>
          <h3 id={`${inputId}-stops`} className="mb-1 text-xs font-semibold tracking-wide text-ink-muted uppercase">
            Stops
          </h3>
          <ul>
            {stops.map((hit) => (
              <StopResult key={hit.id} hit={hit} onNavigate={onNavigate} />
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function describe(status: string, shown: number, query: string): string {
  switch (status) {
    case "idle":
      return "Type a route number or a name. Press Escape to close.";
    case "loading":
      return "Searching…";
    case "ready":
      return shown === 0 ? `No results for ${query}.` : `${shown} ${shown === 1 ? "result" : "results"} for ${query}.`;
    default:
      return "";
  }
}

/** What has come back for a stop. Not having come back yet is not stored: it is a stop that is open with no answer. */
type RoutesAtStop = { status: "ready"; routes: RouteSummary[] } | { status: "error" };

/** One stop, which opens to show the routes that call there. */
function StopResult({ hit, onNavigate }: { hit: SearchHit; onNavigate: () => void }) {
  const [open, setOpen] = useState(false);
  const [served, setServed] = useState<RoutesAtStop | null>(null);
  const listId = useId();

  useEffect(() => {
    if (!open || served !== null) {
      return;
    }
    const controller = new AbortController();
    fetchStopDetail(hit.id, controller.signal).then(
      (detail) => setServed({ status: "ready", routes: detail.routes }),
      () => {
        if (!controller.signal.aborted) {
          setServed({ status: "error" });
        }
      },
    );
    return () => controller.abort();
  }, [open, served, hit.id]);

  return (
    <li>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-baseline gap-2 rounded-md px-2 py-1.5 text-left text-sm text-ink hover:bg-surface-inset"
      >
        <span className="min-w-0 flex-1 truncate">{hit.label}</span>
        {hit.sublabel ? <span className="shrink-0 text-xs text-ink-subtle">Stop {hit.sublabel}</span> : null}
      </button>

      <div id={listId} hidden={!open} className="ml-3 border-l border-border pl-3">
        {open && served === null ? <p className="py-1 text-xs text-ink-muted">Finding the routes…</p> : null}
        {served?.status === "error" ? (
          <p className="py-1 text-xs text-ink-muted">Could not load the routes that call here.</p>
        ) : null}
        {served?.status === "ready" && served.routes.length === 0 ? (
          <p className="py-1 text-xs text-ink-muted">No route in the imported schedule calls here.</p>
        ) : null}
        {served?.status === "ready" && served.routes.length > 0 ? (
          <ul>
            {served.routes.map((route) => (
              <li key={route.routeId}>
                <Link
                  href={{ pathname: "/map", query: { route: route.routeId } }}
                  onClick={onNavigate}
                  className="flex items-center gap-2 rounded-md px-1 py-1 text-sm text-ink hover:bg-surface-inset"
                >
                  <RouteBadge
                    number={route.shortName}
                    mode={route.mode === "OTHER" ? "BUS" : route.mode}
                    colour={route.colour}
                    textColour={route.textColour}
                    size="sm"
                  />
                  <span className="min-w-0 flex-1 truncate text-ink-muted">{route.longName}</span>
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </li>
  );
}
