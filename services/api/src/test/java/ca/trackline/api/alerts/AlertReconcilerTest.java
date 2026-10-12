package ca.trackline.api.alerts;

import java.util.List;

import ca.trackline.api.alerts.AlertReconciler.Change;
import ca.trackline.api.alerts.AlertReconciler.Incoming;
import ca.trackline.api.alerts.AlertReconciler.Open;
import ca.trackline.api.alerts.AlertReconciler.Plan;
import ca.trackline.api.gtfs.realtime.AlertSnapshot;
import ca.trackline.api.gtfs.realtime.AlertSnapshot.AffectedEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every case of the lifecycle: appears, stays, is edited, disappears, comes back.
 */
class AlertReconcilerTest {

    private static AlertSnapshot alert(String id, String description) {
        return new AlertSnapshot(id, 1, 8, "header", description, null, List.of(new AffectedEntity("501", null)));
    }

    /** What the database holds for an alert already seen with this content. */
    private static Open stored(long id, AlertSnapshot alert) {
        return new Open(id, alert.alertId(), AlertContentHash.of(alert));
    }

    @Test
    void anAlertNeverSeenBeforeIsOpened() {
        Plan plan = AlertReconciler.plan(List.of(), List.of(alert("a", "text")));

        assertThat(plan.toOpen()).extracting(incoming -> incoming.alert().alertId()).containsExactly("a");
        assertThat(plan.toChange()).isEmpty();
        assertThat(plan.toConfirm()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void anUnchangedAlertIsConfirmedAndNothingElse() {
        AlertSnapshot same = alert("a", "text");

        Plan plan = AlertReconciler.plan(List.of(stored(7, same)), List.of(same));

        assertThat(plan.toConfirm()).containsExactly(7L);
        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.toChange()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
    }

    @Test
    void anEditedAlertIsChangedInPlaceUnderItsExistingId() {
        Plan plan = AlertReconciler.plan(List.of(stored(7, alert("a", "old text"))), List.of(alert("a", "new text")));

        assertThat(plan.toChange()).singleElement().satisfies(change -> {
            assertThat(change.id()).isEqualTo(7L);
            assertThat(change.incoming().alert().description()).isEqualTo("new text");
        });
        // Edited, not ended and restarted: the stretch and its first_seen_at carry on.
        assertThat(plan.toOpen()).isEmpty();
        assertThat(plan.toResolve()).isEmpty();
        assertThat(plan.toConfirm()).isEmpty();
    }

    @Test
    void anAlertTheFeedNoLongerCarriesIsResolved() {
        Plan plan = AlertReconciler.plan(List.of(stored(7, alert("a", "text"))), List.of());

        assertThat(plan.toResolve()).containsExactly(7L);
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void anAlertThatReturnsAfterBeingResolvedIsANewStretch() {
        // Resolved alerts are not in the "open" list the reconciler is given, so the same
        // publisher id arriving again looks new. That is the intended behaviour: it is a
        // new stretch with its own start, not an extension of the one that ended.
        Plan plan = AlertReconciler.plan(List.of(), List.of(alert("a", "text")));

        assertThat(plan.toOpen()).hasSize(1);
        assertThat(plan.toChange()).isEmpty();
    }

    @Test
    void aMixedCycleSortsEveryAlertIntoExactlyOneOutcome() {
        AlertSnapshot stays = alert("stays", "same");
        AlertSnapshot edited = alert("edited", "v2");
        AlertSnapshot added = alert("added", "new");
        List<Open> open = List.of(
                stored(1, stays), stored(2, alert("edited", "v1")), stored(3, alert("gone", "bye")));

        Plan plan = AlertReconciler.plan(open, List.of(stays, edited, added));

        assertThat(plan.toConfirm()).containsExactly(1L);
        assertThat(plan.toChange()).extracting(Change::id).containsExactly(2L);
        assertThat(plan.toOpen()).extracting(Incoming::alert).extracting(AlertSnapshot::alertId).containsExactly("added");
        assertThat(plan.toResolve()).containsExactly(3L);
    }

    @Test
    void anEmptyFeedResolvesEverythingOpen() {
        // The reconciler does not judge whether an empty feed is believable. The caller
        // does, and must not hand it a payload it does not trust.
        Plan plan = AlertReconciler.plan(
                List.of(stored(1, alert("a", "x")), stored(2, alert("b", "y"))), List.of());

        assertThat(plan.toResolve()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void anIdRepeatedWithinOnePayloadIsOneAlertAndTheLastOccurrenceWins() {
        Plan plan = AlertReconciler.plan(List.of(), List.of(alert("a", "first"), alert("a", "second")));

        assertThat(plan.toOpen()).singleElement()
                .satisfies(incoming -> assertThat(incoming.alert().description()).isEqualTo("second"));
    }

    @Test
    void aRepeatedIdIsNotOpenedTwiceEvenWhenItIsAlreadyStored() {
        AlertSnapshot stored = alert("a", "text");

        Plan plan = AlertReconciler.plan(List.of(stored(7, stored)), List.of(stored, stored));

        assertThat(plan.toConfirm()).containsExactly(7L);
        assertThat(plan.toOpen()).isEmpty();
    }

    @Test
    void reorderingTheEntitiesIsNotAnEdit() {
        AlertSnapshot original = new AlertSnapshot(
                "a", 1, 8, "h", "d", null, List.of(new AffectedEntity("1", null), new AffectedEntity("2", null)));
        AlertSnapshot reordered = new AlertSnapshot(
                "a", 1, 8, "h", "d", null, List.of(new AffectedEntity("2", null), new AffectedEntity("1", null)));

        Plan plan = AlertReconciler.plan(List.of(stored(7, original)), List.of(reordered));

        assertThat(plan.toConfirm()).containsExactly(7L);
        assertThat(plan.toChange()).isEmpty();
    }
}
