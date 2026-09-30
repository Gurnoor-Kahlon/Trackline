"use client";

import { useEffect } from "react";

import { Button } from "@/components/ui/Button";
import { ErrorState } from "@/components/ui/ErrorState";

/**
 * Route-level error boundary. `digest` is Next's own identifier for the failure,
 * which is the closest client-side equivalent of the API's errorId and is worth
 * showing so a report can be tied to a log line.
 */
export default function RouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <ErrorState
      title="This page could not be loaded"
      description="Something failed while rendering. Trying again may be enough; if it is not, the data behind this page may be unavailable."
      errorId={error.digest}
      action={
        <Button variant="primary" onClick={reset}>
          Try again
        </Button>
      }
    />
  );
}
