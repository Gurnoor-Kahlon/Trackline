package ca.trackline.api.alerts;

import java.time.Instant;
import java.util.List;

import ca.trackline.api.routes.TransitMode;

/**
 * Shapes returned by the alert endpoints.
 */
public final class AlertDtos {

    private AlertDtos() {
    }

    /**
     * One stretch of an alert being present in the feed.
     *
     * <p>The feed publishes no start or end, so every time here is one Trackline
     * observed, and each is an upper bound on the moment it describes: the alert began
     * at or before {@code firstSeenAt}, and ended at or before {@code resolvedAt}.
     *
     * @param id              Trackline's identifier for this stretch. An alert that
     *                        disappears and returns is a new stretch with a new id
     * @param sourceAlertId   the publisher's identifier, which a returning alert keeps
     * @param status          ACTIVE while the feed still carries it, RESOLVED once it does not
     * @param cause           GTFS cause as published. The TTC sends UNKNOWN_CAUSE for every alert
     * @param effect          GTFS effect as published. The TTC sends UNKNOWN_EFFECT for every alert
     * @param header          the publisher's short text, which it cuts off at about 31
     *                        characters, mid-word. Prefer {@code description} for display
     * @param description     the full text, or null if the publisher gave none
     * @param url             more information, or null
     * @param firstSeenAt     first cycle that contained the alert
     * @param lastSeenAt      latest cycle that contained it
     * @param lastChangedAt   when the content last differed from what was stored
     * @param resolvedAt      first cycle that did not contain it, or null while active
     * @param routes          routes the alert names, with those the schedule does not know flagged
     * @param stops           stops the alert names, likewise
     */
    public record AlertView(
            long id,
            String sourceAlertId,
            AlertStatus status,
            AlertCause cause,
            AlertEffect effect,
            String header,
            String description,
            String url,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant lastChangedAt,
            Instant resolvedAt,
            List<AffectedRoute> routes,
            List<AffectedStop> stops) {
    }

    public enum AlertStatus {
        ACTIVE,
        RESOLVED
    }

    /**
     * A route an alert names.
     *
     * @param known false when the published id is not in the imported schedule. The
     *              id is kept and shown, since the alert is real even when the
     *              schedule is out of date; the names, colours and mode are then null
     */
    public record AffectedRoute(
            String routeId,
            boolean known,
            String shortName,
            String longName,
            TransitMode mode,
            String colour,
            String textColour) {
    }

    /** A stop an alert names; {@code known} is false and the name null when the schedule lacks it. */
    public record AffectedStop(String stopId, boolean known, String name) {
    }
}
