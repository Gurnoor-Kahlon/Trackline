package ca.trackline.api.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ca.trackline.api.alerts.AlertReconciler.Change;
import ca.trackline.api.alerts.AlertReconciler.Incoming;
import ca.trackline.api.alerts.AlertReconciler.Open;
import ca.trackline.api.alerts.AlertReconciler.Plan;
import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps the stored alerts in step with the feed, and owns their lifecycle.
 *
 * <p>Applying a payload is one transaction: a cycle either applies completely or leaves the
 * stored alerts as they were, so a failure halfway cannot resolve some alerts on the
 * strength of a payload it then failed to finish reading.
 *
 * <p>Whether a payload deserves to be applied is not decided here. A caller must not
 * hand over a cycle it does not trust, because anything missing from the feed is
 * resolved, and a feed that has momentarily emptied would otherwise end every alert.
 */
@Service
public class AlertLifecycleService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ActiveAlerts activeAlerts;

    public AlertLifecycleService(
            JdbcTemplate jdbc, PlatformTransactionManager transactions, ActiveAlerts activeAlerts) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.activeAlerts = activeAlerts;
    }

    /** What one cycle did to the stored alerts. */
    public record Outcome(int opened, int changed, int confirmed, int resolved) {

        public int openNow() {
            return opened + changed + confirmed;
        }
    }

    /**
     * Applies a payload.
     *
     * @param feed the alerts the feed carries now
     * @param now  when it was seen
     */
    public Outcome apply(List<AlertSnapshot> feed, Instant now) {
        Outcome outcome = transaction.execute(status -> applyInTransaction(feed, now));
        // After the commit, so the count never reports a state that was rolled back.
        activeAlerts.record(outcome.openNow());
        return outcome;
    }

    private Outcome applyInTransaction(List<AlertSnapshot> feed, Instant now) {
        List<Open> open = jdbc.query(
                "SELECT id, source_alert_id, content_hash FROM service_alerts WHERE resolved_at IS NULL",
                (rows, index) -> new Open(
                        rows.getLong("id"), rows.getString("source_alert_id"), rows.getString("content_hash")));

        Plan plan = AlertReconciler.plan(open, feed);
        Timestamp at = Timestamp.from(now);

        for (Incoming incoming : plan.toOpen()) {
            AlertSnapshot alert = incoming.alert();
            Long id = jdbc.queryForObject(
                    """
                    INSERT INTO service_alerts (
                        source_alert_id, cause, effect, header, description, url, content_hash,
                        first_seen_at, last_seen_at, last_changed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    alert.alertId(), alert.cause(), alert.effect(), alert.header(), alert.description(),
                    alert.url(), incoming.contentHash(), at, at, at);
            insertEntities(id, alert.affected());
        }

        for (Change change : plan.toChange()) {
            AlertSnapshot alert = change.incoming().alert();
            jdbc.update(
                    """
                    UPDATE service_alerts
                    SET cause = ?, effect = ?, header = ?, description = ?, url = ?, content_hash = ?,
                        last_seen_at = GREATEST(last_seen_at, ?), last_changed_at = GREATEST(last_changed_at, ?)
                    WHERE id = ?
                    """,
                    alert.cause(), alert.effect(), alert.header(), alert.description(), alert.url(),
                    change.incoming().contentHash(), at, at, change.id());
            jdbc.update("DELETE FROM service_alert_entities WHERE alert_id = ?", change.id());
            insertEntities(change.id(), alert.affected());
        }

        jdbc.batchUpdate(
                "UPDATE service_alerts SET last_seen_at = GREATEST(last_seen_at, ?) WHERE id = ?",
                plan.toConfirm(),
                plan.toConfirm().size() + 1,
                (statement, id) -> {
                    statement.setTimestamp(1, at);
                    statement.setLong(2, id);
                });

        // GREATEST keeps the stored order intact if the clock has stepped backwards: a
        // resolution can never claim to be earlier than the last time the alert was seen.
        jdbc.batchUpdate(
                "UPDATE service_alerts SET resolved_at = GREATEST(?, last_seen_at) WHERE id = ?",
                plan.toResolve(),
                plan.toResolve().size() + 1,
                (statement, id) -> {
                    statement.setTimestamp(1, at);
                    statement.setLong(2, id);
                });

        return new Outcome(
                plan.toOpen().size(), plan.toChange().size(), plan.toConfirm().size(), plan.toResolve().size());
    }

    /**
     * Records that the feed was fetched again and said the same as before, so the
     * alerts it carries were seen now and not only when the publisher last changed.
     *
     * @return how many open alerts were confirmed
     */
    public int confirmUnchanged(Instant now) {
        return jdbc.update(
                "UPDATE service_alerts SET last_seen_at = GREATEST(last_seen_at, ?) WHERE resolved_at IS NULL",
                Timestamp.from(now));
    }

    /**
     * Stored once per distinct pair, and only when it names a route or a stop. The feed
     * repeats pairs within an alert, and an entity that names neither (an agency, say)
     * has nothing to resolve against.
     */
    private void insertEntities(Long alertId, List<AffectedEntity> affected) {
        Set<AffectedEntity> distinct = new LinkedHashSet<>();
        for (AffectedEntity entity : affected) {
            if (entity.routeId() != null || entity.stopId() != null) {
                distinct.add(entity);
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO service_alert_entities (alert_id, route_id, stop_id) VALUES (?, ?, ?)",
                distinct,
                Math.max(1, distinct.size()),
                (statement, entity) -> {
                    statement.setLong(1, alertId);
                    statement.setString(2, entity.routeId());
                    statement.setString(3, entity.stopId());
                });
    }
}
