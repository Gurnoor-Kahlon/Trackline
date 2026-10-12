package ca.trackline.api.alerts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ca.trackline.api.gtfs.realtime.AlertSnapshot;

/**
 * Decides what a cycle's alerts mean for the stored ones.
 *
 * <p>Pure: it is given what is stored and what the feed now says, and returns what
 * to do about each, so every case of the lifecycle can be tested without a database.
 *
 * <p>For each alert in the feed:
 * <ul>
 *   <li>not stored as open: it is <b>opened</b>. This covers a brand-new alert and
 *       one that disappeared and came back, which is a new stretch;</li>
 *   <li>stored as open with the same content: it is <b>confirmed</b>;</li>
 *   <li>stored as open with different content: it is <b>changed</b>, in place.</li>
 * </ul>
 * Every stored open alert the feed no longer carries is <b>resolved</b>.
 */
final class AlertReconciler {

    private AlertReconciler() {
    }

    /** What is stored for an alert that is currently open. */
    record Open(long id, String sourceAlertId, String contentHash) {
    }

    record Incoming(AlertSnapshot alert, String contentHash) {
    }

    record Change(long id, Incoming incoming) {
    }

    record Plan(List<Incoming> toOpen, List<Change> toChange, List<Long> toConfirm, List<Long> toResolve) {
    }

    static Plan plan(Collection<Open> open, List<AlertSnapshot> feed) {
        Map<String, Open> openBySource = new LinkedHashMap<>();
        for (Open stored : open) {
            openBySource.put(stored.sourceAlertId(), stored);
        }

        // An id repeated within one payload is one alert: the last occurrence wins, which
        // is the one a reader of the feed in order would end up with.
        Map<String, AlertSnapshot> distinct = new LinkedHashMap<>();
        for (AlertSnapshot alert : feed) {
            distinct.put(alert.alertId(), alert);
        }

        List<Incoming> toOpen = new ArrayList<>();
        List<Change> toChange = new ArrayList<>();
        List<Long> toConfirm = new ArrayList<>();

        for (AlertSnapshot alert : distinct.values()) {
            Incoming incoming = new Incoming(alert, AlertContentHash.of(alert));
            Open stored = openBySource.remove(alert.alertId());
            if (stored == null) {
                toOpen.add(incoming);
            } else if (stored.contentHash().equals(incoming.contentHash())) {
                toConfirm.add(stored.id());
            } else {
                toChange.add(new Change(stored.id(), incoming));
            }
        }

        // Whatever was not claimed above is open in the database and gone from the feed.
        List<Long> toResolve = openBySource.values().stream().map(Open::id).toList();
        return new Plan(List.copyOf(toOpen), List.copyOf(toChange), List.copyOf(toConfirm), toResolve);
    }
}
