package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A scheduled trip.
 *
 * <p>Realtime trip identifiers do not join to this table (a measured 2 of 1,050
 * matched), so this serves schedule questions and supplies each shape's
 * direction. It is never used to locate a live vehicle.
 */
@Entity
@Table(name = "trips")
public class TripEntity {

    @Id
    @Column(name = "trip_id", nullable = false)
    private String tripId;

    @Column(name = "feed_version_id", nullable = false)
    private Long feedVersionId;

    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "service_id", nullable = false)
    private String serviceId;

    /** 0 or 1. Absent from the realtime vehicle feed, so this is the only source of truth for direction. */
    @Column(name = "direction_id", nullable = false)
    private short directionId;

    @Column(name = "headsign")
    private String headsign;

    @Column(name = "shape_id")
    private String shapeId;

    @Column(name = "block_id")
    private String blockId;

    protected TripEntity() {
        // Required by JPA.
    }

    public TripEntity(String tripId, Long feedVersionId, String routeId, String serviceId, short directionId, String headsign, String shapeId, String blockId) {
        this.tripId = tripId;
        this.feedVersionId = feedVersionId;
        this.routeId = routeId;
        this.serviceId = serviceId;
        this.directionId = directionId;
        this.headsign = headsign;
        this.shapeId = shapeId;
        this.blockId = blockId;
    }

    public String getTripId() {
        return tripId;
    }

    public Long getFeedVersionId() {
        return feedVersionId;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getServiceId() {
        return serviceId;
    }

    public short getDirectionId() {
        return directionId;
    }

    public String getHeadsign() {
        return headsign;
    }

    public String getShapeId() {
        return shapeId;
    }

    public String getBlockId() {
        return blockId;
    }

}
