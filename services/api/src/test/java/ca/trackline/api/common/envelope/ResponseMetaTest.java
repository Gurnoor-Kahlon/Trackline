package ca.trackline.api.common.envelope;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseMetaTest {

    private static final Instant NOW = Instant.parse("2026-09-27T18:00:00Z");

    @Test
    void noDataReportsUnknownAgeRatherThanZero() {
        ResponseMeta meta = ResponseMeta.noData(NOW);

        assertThat(meta.source()).isEqualTo(DataSource.NO_DATA);
        assertThat(meta.feedTimestamp()).isNull();
        assertThat(meta.ageSeconds()).isNull();
        assertThat(meta.stale()).isTrue();
        assertThat(meta.degraded()).isFalse();
    }

    @Test
    void feedAgeIsDerivedFromTheFeedTimestamp() {
        ResponseMeta meta = ResponseMeta.fromFeed(NOW, NOW.minusSeconds(12), false, DataSource.LIVE);

        assertThat(meta.ageSeconds()).isEqualTo(12L);
        assertThat(meta.stale()).isFalse();
    }

    @Test
    void dataOlderThanTheStaleThresholdIsFlaggedStale() {
        Instant justInside = NOW.minusSeconds(ResponseMeta.STALE_AFTER.toSeconds());
        Instant justOutside = NOW.minusSeconds(ResponseMeta.STALE_AFTER.toSeconds() + 1);

        assertThat(ResponseMeta.fromFeed(NOW, justInside, false, DataSource.LIVE).stale()).isFalse();
        assertThat(ResponseMeta.fromFeed(NOW, justOutside, false, DataSource.LIVE).stale()).isTrue();
    }

    @Test
    void degradationIsCarriedThroughSeparatelyFromStaleness() {
        ResponseMeta meta = ResponseMeta.fromFeed(NOW, NOW.minusSeconds(5), true, DataSource.DB_FALLBACK);

        assertThat(meta.degraded()).isTrue();
        assertThat(meta.stale()).isFalse();
        assertThat(meta.source()).isEqualTo(DataSource.DB_FALLBACK);
    }

    @Test
    void staticDataHasNoFreshnessDimension() {
        ResponseMeta meta = ResponseMeta.forStatic(NOW);

        assertThat(meta.source()).isEqualTo(DataSource.STATIC);
        assertThat(meta.stale()).isFalse();
        assertThat(meta.ageSeconds()).isNull();
    }
}
