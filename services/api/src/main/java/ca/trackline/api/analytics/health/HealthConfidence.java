package ca.trackline.api.analytics.health;

/**
 * How far a verdict can be trusted, which depends on how much it was based on.
 *
 * <p>A verdict from two vehicles and one gap is a weaker claim than one from nine
 * vehicles compared with the timetable, and the response says so instead of presenting
 * them alike.
 */
public enum HealthConfidence {

    /** No verdict was reached. */
    NONE(0),

    /** Two vehicles, or many of them stale. */
    LOW(1),

    /** Three or four vehicles, or no timetable to compare against. */
    MEDIUM(2),

    /** Five or more fresh vehicles compared against the timetable. */
    HIGH(3);

    private final int rank;

    HealthConfidence(int rank) {
        this.rank = rank;
    }

    public boolean isLowerThan(HealthConfidence other) {
        return rank < other.rank;
    }
}
