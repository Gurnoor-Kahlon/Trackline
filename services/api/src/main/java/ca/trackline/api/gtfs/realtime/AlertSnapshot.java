package ca.trackline.api.gtfs.realtime;

import java.util.List;

/**
 * One service alert.
 *
 * <p>The feed supplies no active period and no severity — measured across every
 * alert in a real snapshot — so an alert carries no start, no end and no ranking.
 * Trackline observes when an alert appears and disappears instead, which is why
 * alerts need persistence rather than being read straight from the feed.
 *
 * @param alertId     publisher's identifier, stable while the alert is live
 * @param cause       GTFS Cause ordinal, or null
 * @param effect      GTFS Effect ordinal, or null
 * @param header      short description
 * @param description longer text, or null
 * @param url         more information, or null
 * @param affected    what the alert applies to
 */
public record AlertSnapshot(
        String alertId,
        Integer cause,
        Integer effect,
        String header,
        String description,
        String url,
        List<AffectedEntity> affected) {

    /**
     * What an alert informs about. Either may be null: an alert can name a route, a
     * stop, or a stop on a route.
     */
    public record AffectedEntity(String routeId, String stopId) {
    }
}
