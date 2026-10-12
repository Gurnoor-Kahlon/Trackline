package ca.trackline.api.stream;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resuming is only allowed when it can be proved correct.
 *
 * <p>The failure to prevent is silent: a client told "here is what you missed" when
 * something else was missed too, and left showing a picture that never existed.
 */
class EventRingTest {

    private static StreamEvent event(long id, long previousId) {
        return new StreamEvent(
                id, previousId, StreamEvent.Kind.DELTA, Instant.EPOCH, 0, 0, List.of(), List.of(), List.of());
    }

    /** A ring holding events 10, 20, 30, 40, each chained to the one before. */
    private static EventRing ringOfFour(int capacity) {
        EventRing ring = new EventRing(capacity);
        ring.append(event(10, 0));
        ring.append(event(20, 10));
        ring.append(event(30, 20));
        ring.append(event(40, 30));
        return ring;
    }

    private static List<Long> ids(List<StreamEvent> events) {
        return events.stream().map(StreamEvent::id).toList();
    }

    @Test
    void anEmptyRingHasNoLatestIdAndCannotResumeAnyone() {
        EventRing ring = new EventRing(5);

        assertThat(ring.latestId()).isZero();
        assertThat(ring.replayAfter(10)).isEmpty();
    }

    @Test
    void aClientGetsExactlyTheEventsAfterTheOneItHas() {
        EventRing ring = ringOfFour(10);

        assertThat(ring.replayAfter(20)).hasValueSatisfying(events -> assertThat(ids(events)).containsExactly(30L, 40L));
        assertThat(ring.replayAfter(10)).hasValueSatisfying(events -> assertThat(ids(events)).containsExactly(20L, 30L, 40L));
    }

    @Test
    void aClientAtTheNewestEventIsCurrentWhichIsNotTheSameAsCannotResume() {
        EventRing ring = ringOfFour(10);

        // Present and empty: "you have everything". An empty Optional would mean "I
        // cannot say", and the client would be needlessly sent a whole snapshot.
        assertThat(ring.replayAfter(40)).hasValueSatisfying(events -> assertThat(events).isEmpty());
    }

    @Test
    void anIdThatWasNeverIssuedCannotResume() {
        EventRing ring = ringOfFour(10);

        assertThat(ring.replayAfter(25)).isEmpty();
        assertThat(ring.replayAfter(999)).isEmpty();
    }

    @Test
    void anIdFromBeforeTheRingBeganCannotResume() {
        EventRing ring = ringOfFour(10);

        // The first event chains from 0, so a client that "has" id 5 is not on this chain.
        assertThat(ring.replayAfter(5)).isEmpty();
    }

    @Test
    void anIdThatHasFallenOutOfTheRingCannotResume() {
        EventRing ring = ringOfFour(2);   // keeps 30 and 40 only

        // 10 and 20 were evicted. A client at 10 missed 20, which is gone, so it must
        // not be told 30 and 40 are everything it missed.
        assertThat(ring.replayAfter(10)).isEmpty();
        assertThat(ring.size()).isEqualTo(2);
    }

    @Test
    void theOldestRetainedEventCanStillBeResumedFromItsPredecessor() {
        EventRing ring = ringOfFour(2);   // keeps 30 (previous 20) and 40

        assertThat(ring.replayAfter(20)).hasValueSatisfying(events -> assertThat(ids(events)).containsExactly(30L, 40L));
    }

    @Test
    void eventsThatDoNotFormAnUnbrokenChainAreNotReplayed() {
        EventRing ring = new EventRing(10);
        ring.append(event(10, 0));
        ring.append(event(20, 10));
        ring.append(event(40, 30));   // claims to follow 30, which is not in the ring

        assertThat(ring.replayAfter(10)).isEmpty();
    }

    @Test
    void nextIdFollowsTheClockWhenTheClockIsAhead() {
        EventRing ring = ringOfFour(10);

        assertThat(ring.nextId(1_000_000)).isEqualTo(1_000_000);
    }

    @Test
    void nextIdNeverGoesBackwardsEvenIfTheClockDoes() {
        EventRing ring = ringOfFour(10);

        assertThat(ring.nextId(5)).isEqualTo(41);
    }

    @Test
    void aClearedRingForgetsEverything() {
        EventRing ring = ringOfFour(10);

        ring.clear();

        assertThat(ring.latestId()).isZero();
        assertThat(ring.replayAfter(30)).isEmpty();
    }
}
