import type { Metadata } from "next";

import { AlertsView } from "@/components/alerts/AlertsView";

export const metadata: Metadata = {
  title: "Alerts",
  description:
    "Service alerts from the TTC's realtime feed, with when Trackline first and last saw each one.",
};

/**
 * Alerts.
 *
 * Rendered as a shell with the list fetched in the browser, because the list changes
 * by the minute and a prerendered page would show whatever was true at build time. The
 * heading is here, in the server component, so the page is identifiable before the list
 * arrives and whether or not it ever does.
 */
export default function AlertsPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold tracking-tight text-ink">Service alerts</h1>
      <AlertsView />
    </div>
  );
}
