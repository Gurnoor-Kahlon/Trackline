package ca.trackline.api.analytics.bunching;

/** The detector's documented thresholds, for tests that do not care to spell them out. */
public final class BunchingFixtures {

    private BunchingFixtures() {
    }

    public static BunchingProperties defaults() {
        return new BunchingProperties(0.35, 1.8, 2.5, 3, 3, 4, 2, 2, 3);
    }
}
