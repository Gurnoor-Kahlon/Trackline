package ca.trackline.api.persistence.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One scheduled call at a stop.
 *
 * <p>The largest table in the feed at roughly 4.36 million rows, so it is loaded
 * with {@code COPY} rather than through this entity; the mapping exists for
 * schedule reads.
 *
 * <p>Times are seconds after midnight rather than clock times, because GTFS
 * legitimately expresses trips running past midnight as 24:00:00 and beyond,
 * which no time-of-day type can hold.
 */
@Entity
@Table(name = "stop_times")
@IdClass(StopTimeEntity.StopTimeId.class)
public class StopTimeEntity {

    @Id
    @Column(name = "trip_id", nullable = false)
    private String tripId;

    @Id
    @Column(name = "stop_sequence", nullable = false)
    private int stopSequence;

    @Column(name = "stop_id", nullable = false)
    private String stopId;

    @Column(name = "arrival_seconds", nullable = false)
    private int arrivalSeconds;

    @Column(name = "departure_seconds", nullable = false)
    private int departureSeconds;

    /** Metres along the trip's shape, where the feed provides it. */
    @Column(name = "shape_dist_traveled")
    private BigDecimal shapeDistTraveled;

    protected StopTimeEntity() {
        // Required by JPA.
    }

    public StopTimeEntity(
            String tripId,
            int stopSequence,
            String stopId,
            int arrivalSeconds,
            int departureSeconds,
            BigDecimal shapeDistTraveled) {
        this.tripId = tripId;
        this.stopSequence = stopSequence;
        this.stopId = stopId;
        this.arrivalSeconds = arrivalSeconds;
        this.departureSeconds = departureSeconds;
        this.shapeDistTraveled = shapeDistTraveled;
    }

    public String getTripId() {
        return tripId;
    }

    public int getStopSequence() {
        return stopSequence;
    }

    public String getStopId() {
        return stopId;
    }

    public int getArrivalSeconds() {
        return arrivalSeconds;
    }

    public int getDepartureSeconds() {
        return departureSeconds;
    }

    public BigDecimal getShapeDistTraveled() {
        return shapeDistTraveled;
    }

    /** Composite key: a stop position within a trip. */
    public record StopTimeId(String tripId, int stopSequence) implements Serializable {

        public StopTimeId {
            Objects.requireNonNull(tripId, "tripId");
        }
    }
}
