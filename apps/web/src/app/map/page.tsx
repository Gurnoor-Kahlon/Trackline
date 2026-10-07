import type { Metadata } from "next";

import { MapExplorer } from "@/components/map/MapExplorer";
import { EmptyState } from "@/components/ui/EmptyState";
import { ErrorState } from "@/components/ui/ErrorState";
import { ApiError } from "@/lib/api/client";
import { fetchAllRoutes } from "@/lib/api/routes";
import type { RouteSummary } from "@/lib/api/types";

/**
 * Rendered per request rather than at build time.
 *
 * Whether the API is reachable is a fact about right now, not about the build.
 * Prerendering this page captures whichever of the three outcomes below happened
 * to hold when it was compiled — and if the API was down then, every visitor is
 * served that error until the page revalidates. The route list itself is still
 * cached, by the fetch that asks for it, so this costs a render and not a request.
 */
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Map",
  description:
    "Every TTC route drawn from the agency's published schedule geometry, with the stops each one serves.",
};

/**
 * The map.
 *
 * The route list is fetched on the server so the page arrives with its routes
 * already in it — there are 236 of them and they change monthly, which is exactly
 * what server rendering and a cached fetch are for. Geometry stays on the client,
 * because it depends on what the user selects.
 */
export default async function MapPage() {
  let routes: RouteSummary[];
  try {
    routes = await fetchAllRoutes();
  } catch (cause) {
    // Rendered rather than thrown. Throwing would hand the page to the error
    // boundary, which can only say something generic; the API being unreachable
    // is a specific, actionable thing to tell someone running this locally.
    return (
      <Page>
        <ErrorState
          title="Routes could not be loaded"
          description={
            cause instanceof ApiError && cause.isResponse
              ? cause.message
              : "The Trackline API could not be reached. Start it and reload this page."
          }
          {...(cause instanceof ApiError && cause.errorId !== undefined
            ? { errorId: cause.errorId }
            : {})}
        />
      </Page>
    );
  }

  if (routes.length === 0) {
    return (
      <Page>
        <EmptyState
          title="No routes have been imported"
          description="The API is running but its database holds no TTC schedule data, so there is no geometry to draw. Import the static feed and reload this page."
        />
      </Page>
    );
  }

  return (
    <Page>
      <MapExplorer routes={routes} />
    </Page>
  );
}

/**
 * The page's heading and framing, shared by all three outcomes so the page keeps
 * its identity whether or not the data behind it arrived.
 */
function Page({ children }: { children: React.ReactNode }) {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-ink">Map</h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-muted">
          Every route the TTC publishes, drawn from its own schedule geometry. Choose a route to
          see its path and the stops it serves; stop markers appear as you zoom in.
        </p>
      </div>
      {children}
    </div>
  );
}
