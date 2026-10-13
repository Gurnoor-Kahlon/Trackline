package ca.trackline.api.analytics.health;

/**
 * How coherent service on a route is right now.
 *
 * <p>Ordinal, not a score. A single number would need weights that nothing here can
 * justify, so the verdict is one of five named states with the figures behind it
 * returned beside it. The names are part of the API and of the web client's vocabulary.
 */
public enum RouteHealthState {

    EXCELLENT(0),
    NORMAL(1),
    UNSTABLE(2),
    MAJOR_DISRUPTION(3),

    /** Not enough to judge. Never a worse or better state: a statement that there is no verdict. */
    UNKNOWN(-1);

    private final int severity;

    RouteHealthState(int severity) {
        this.severity = severity;
    }

    /** True for the four states that are an actual verdict. */
    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /** Higher is worse. UNKNOWN is -1 and is never compared as a severity. */
    public int severity() {
        return severity;
    }

    public static RouteHealthState worse(RouteHealthState first, RouteHealthState second) {
        if (!first.isKnown()) {
            return second;
        }
        if (!second.isKnown()) {
            return first;
        }
        return first.severity >= second.severity ? first : second;
    }
}
