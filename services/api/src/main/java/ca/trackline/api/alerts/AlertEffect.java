package ca.trackline.api.alerts;

/**
 * The GTFS-Realtime {@code Alert.Effect} values, by their published numbers.
 *
 * <p>The TTC currently sends {@link #UNKNOWN_EFFECT} on every alert, so nothing can
 * be grouped or ranked by effect. It is reported as published; Trackline does not
 * infer an effect from the wording of the text.
 */
public enum AlertEffect {
    NO_SERVICE(1),
    REDUCED_SERVICE(2),
    SIGNIFICANT_DELAYS(3),
    DETOUR(4),
    ADDITIONAL_SERVICE(5),
    MODIFIED_SERVICE(6),
    OTHER_EFFECT(7),
    UNKNOWN_EFFECT(8),
    STOP_MOVED(9),
    NO_EFFECT(10),
    ACCESSIBILITY_ISSUE(11);

    private final int number;

    AlertEffect(int number) {
        this.number = number;
    }

    /** The name for a published number, or null when there is none or the specification does not define it. */
    public static AlertEffect fromNumber(Integer number) {
        if (number == null) {
            return null;
        }
        for (AlertEffect effect : values()) {
            if (effect.number == number) {
                return effect;
            }
        }
        return null;
    }
}
