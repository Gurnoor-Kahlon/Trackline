import type { ReactNode } from "react";

import { cn } from "@/lib/cn";

type EmptyStateProps = {
  title: string;
  /** Why it is empty. Never implies a failure when nothing is wrong. */
  description?: string | undefined;
  action?: ReactNode;
  className?: string | undefined;
};

/**
 * Nothing to show, and that is not an error.
 *
 * Trackline needs this state often and honestly: a route with no vehicles
 * running, a subway line with no realtime feed, analytics with too little
 * history. Each of those is a fact about the data, so the copy explains the
 * reason rather than apologising.
 */
export function EmptyState({ title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cn("px-4 py-10 text-center", className)}>
      <p className="text-sm font-semibold text-ink">{title}</p>
      {description ? (
        <p className="mx-auto mt-1.5 max-w-prose text-sm text-ink-muted">{description}</p>
      ) : null}
      {action ? <div className="mt-4 flex justify-center">{action}</div> : null}
    </div>
  );
}
