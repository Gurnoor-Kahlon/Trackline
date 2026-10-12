import { HomeAlerts } from "@/components/alerts/HomeAlerts";
import { Card, CardBody, CardHeader } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { FreshnessChip } from "@/components/ui/FreshnessChip";

/**
 * Home.
 *
 * The structure the finished page uses is here, but every data region reports
 * that nothing has been measured, because no feed has been ingested yet. That is
 * the truth about the system today, so it is what the page says — nothing is
 * filled with plausible-looking numbers to make the layout look finished.
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

      <Card as="section" ariaLabelledBy="network-heading">
        <CardHeader
          id="network-heading"
          title="Network status"
          action={<FreshnessChip ageSeconds={null} />}
        />
        <CardBody className="pt-2">
          <EmptyState
            title="No live data has been recorded yet"
            description="Realtime ingestion is not running, so there are no vehicle counts to report. This panel will show vehicles tracked, routes reporting and active alerts once it is."
          />
        </CardBody>
      </Card>

      <HomeAlerts />

      <Card as="section" ariaLabelledBy="routes-heading">
        <CardHeader id="routes-heading" title="Routes" />
        <CardBody className="pt-2">
          <EmptyState
            title="No routes have been imported"
            description="The TTC schedule feed has not been loaded into the database yet. Once it is, routes appear here with their current service status."
          />
        </CardBody>
      </Card>
    </div>
  );
}
