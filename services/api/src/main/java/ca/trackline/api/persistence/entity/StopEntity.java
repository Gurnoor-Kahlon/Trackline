package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

/**
 * A stop or subway platform. Its location is a geography point, which is what
 * nearest-stop and nearby-transit queries run against.
 */
@Entity
@Table(name = "stops")
public class StopEntity {

    @Id
    @Column(name = "stop_id", nullable = false)
    private String stopId;

    @Column(name = "feed_version_id", nullable = false)
    private Long feedVersionId;

    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "location", nullable = false)
    private Point location;

    /** GTFS value exactly as published: 0 unknown, 1 accessible, 2 not accessible. Never inferred. */
    @Column(name = "wheelchair_boarding", nullable = false)
    private short wheelchairBoarding;

    /** Set by station derivation; null before that runs, and null for a stop that stands alone. */
    @Column(name = "station_id")
    private Long stationId;

    protected StopEntity() {
        // Required by JPA.
    }

    public StopEntity(String stopId, Long feedVersionId, String code, String name, Point location, short wheelchairBoarding) {
        this.stopId = stopId;
        this.feedVersionId = feedVersionId;
        this.code = code;
        this.name = name;
        this.location = location;
        this.wheelchairBoarding = wheelchairBoarding;
    }

    public String getStopId() {
        return stopId;
    }

    public Long getFeedVersionId() {
        return feedVersionId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Point getLocation() {
        return location;
    }

    public short getWheelchairBoarding() {
        return wheelchairBoarding;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

}
