package ca.trackline.api.analytics.health;

/**
 * Why a verdict is what it is, or why there is none.
 *
 * <p>Codes, not sentences: the client composes the wording from these and the figures
 * beside them, so the explanation can never say something the numbers do not.
 */
public enum ReasonCode {

    // --- why a verdict was reached ---

    /** Spacing between vehicles varies more than the unstable band allows. */
    HIGH_HEADWAY_VARIATION,

    /** The largest gap is a large multiple of the expected spacing. */
    LARGE_GAP,

    /** Far fewer vehicles are running than the timetable calls for. */
    FEWER_VEHICLES_THAN_SCHEDULED,

    // --- why a verdict is weaker than it could be ---

    /** Only two vehicles, so one gap is all there is to judge. */
    FEW_VEHICLES,

    /** A large share of the vehicles have not reported recently. */
    STALE_VEHICLES,

    /** The imported timetable does not describe today, so spacing could not be compared with it. */
    NO_SCHEDULE_COVERAGE,

    /** The timetable has no scheduled spacing for this route, direction and hour. */
    NO_SCHEDULED_HEADWAY,

    /** The route's speed is not yet known, so scheduled spacing could not be turned into a distance. */
    SPEED_UNKNOWN,

    // --- why there is no verdict ---

    /** Fewer than two usable vehicles. */
    INSUFFICIENT_VEHICLES,

    /** The TTC publishes no vehicle positions for this route, as with the subway. */
    NO_REALTIME_FEED,

    /** No vehicle snapshot has been analysed yet. */
    NOT_ANALYSED_YET
}
