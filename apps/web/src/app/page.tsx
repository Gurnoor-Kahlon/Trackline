import { HomeAlerts } from "@/components/alerts/HomeAlerts";
import { HomeFavourites } from "@/components/favourites/HomeFavourites";
import { HomeNetworkStatus } from "@/components/health/HomeNetworkStatus";
import { HomeRouteHealth } from "@/components/health/HomeRouteHealth";

/**
 * Home.
 *
 * Every region reads the API and says what it measured and how old it is. Where nothing has
 * been measured a region says that, rather than showing figures that would claim something.
 */
export default function HomePage() {
  return (
    <div className="space-y-6">
      <section aria-labelledby="intro-heading">
        <h1 id="intro-heading" className="text-2xl font-bold tracking-tight text-ink sm:text-3xl">
          What is happening on Toronto transit right now?
        </h1>
        <p className="mt-2 max-w-2xl text-sm text-ink-muted">
          Trackline turns the TTC&apos;s public schedule and realtime feeds into live service
          information: where vehicles are, how evenly they are spaced, and which routes are
          struggling.
        </p>
      </section>

      {/* Only for someone signed in; for anyone else it draws nothing at all. */}
      <HomeFavourites />

      <HomeNetworkStatus />

      <HomeRouteHealth />

      <HomeAlerts />
    </div>
  );
}
