import Link from "next/link";

import { EmptyState } from "@/components/ui/EmptyState";

export default function NotFound() {
  return (
    <EmptyState
      title="That page does not exist"
      description="The link may be out of date, or the route, stop or station may no longer be in the TTC feed."
      action={
        // A link, not a button: this navigates, so it must be a real anchor for
        // middle-click, copy-link and screen-reader role to behave correctly.
        <Link
          href="/"
          className="inline-flex items-center justify-center rounded-md bg-accent px-3 py-2 text-sm font-medium text-surface transition-colors hover:bg-accent-hover"
        >
          Back to Trackline
        </Link>
      }
    />
  );
}
