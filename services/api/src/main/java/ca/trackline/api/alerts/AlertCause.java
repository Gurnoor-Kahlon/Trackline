package ca.trackline.api.alerts;

/**
 * The GTFS-Realtime {@code Alert.Cause} values, by their published numbers.
 *
 * <p>Kept as the specification names them. The TTC currently sends
 * {@link #UNKNOWN_CAUSE} on every alert, and that is reported as it is rather than
 * guessed at from the text.
 */
public enum AlertCause {
    UNKNOWN_CAUSE(1),
    OTHER_CAUSE(2),
    TECHNICAL_PROBLEM(3),
    STRIKE(4),
    DEMONSTRATION(5),
    ACCIDENT(6),
    HOLIDAY(7),
    WEATHER(8),
    MAINTENANCE(9),
    CONSTRUCTION(10),
    POLICE_ACTIVITY(11),
    MEDICAL_EMERGENCY(12);

    private final int number;

    AlertCause(int number) {
        this.number = number;
    }

    /** The name for a published number, or null when there is none or the specification does not define it. */
    public static AlertCause fromNumber(Integer number) {
        if (number == null) {
            return null;
        }
        for (AlertCause cause : values()) {
            if (cause.number == number) {
                return cause;
            }
        }
        return null;
    }
}
