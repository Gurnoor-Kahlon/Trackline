package ca.trackline.api.gtfs.realtime;

/**
 * A realtime response could not be understood.
 *
 * <p>Always fails the whole cycle. These feeds are published as complete datasets,
 * so a payload that only partly parses describes an unknown subset of the network;
 * keeping the previous snapshot is strictly better than replacing it with that.
 */
public class GtfsRealtimeFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GtfsRealtimeFormatException(String message) {
        super(message);
    }

    public GtfsRealtimeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
