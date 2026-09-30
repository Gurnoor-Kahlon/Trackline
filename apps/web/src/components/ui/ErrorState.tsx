import type { ReactNode } from "react";

import { cn } from "@/lib/cn";

type ErrorStateProps = {
  title: string;
  description?: string | undefined;
  /** Short identifier from the API's problem detail, if one was returned. */
  errorId?: string | undefined;
  action?: ReactNode;
  className?: string | undefined;
};

/**
 * Something actually failed.
 *
 * Kept visually distinct from {@link EmptyState}: conflating "no data" with
 * "broken" is how a UI ends up implying the TTC has stopped running when a fetch
 * merely timed out. `role="alert"` so the failure is announced immediately.
 */
export function ErrorState({ title, description, errorId, action, className }: ErrorStateProps) {
  return (
    <div role="alert" className={cn("px-4 py-10 text-center", className)}>
      <p className="text-sm font-semibold text-status-major">{title}</p>
      {description ? (
        <p className="mx-auto mt-1.5 max-w-prose text-sm text-ink-muted">{description}</p>
      ) : null}
      {errorId ? (
        <p className="tabular mt-3 text-xs text-ink-subtle">
          Reference <span className="font-medium text-ink-muted">{errorId}</span>
        </p>
      ) : null}
      {action ? <div className="mt-4 flex justify-center">{action}</div> : null}
    </div>
  );
}
