package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.LineString;

/**
 * A route variant's geometry, held in two projections on purpose.
 *
 * <p>{@link #getGeom()} is metre-based (EPSG:3857) because
 * {@code ST_LineLocatePoint} and length arithmetic need planar units;
 * {@link #getGeomWgs()} is a geography for display and true-distance checks.
 * Live vehicles are projected onto the primary shape of a route and direction.
 */
@Entity
@Table(name = "route_shapes")
public class RouteShapeEntity {

    @Id
    @Column(name = "shape_id", nullable = false)
    private String shapeId;

    @Column(name = "feed_version_id", nullable = false)
    private Long feedVersionId;

    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "direction_id", nullable = false)
    private short directionId;

    /** EPSG:3857, metres. */
    @Column(name = "geom", nullable = false)
    private LineString geom;

    /** EPSG:4326 geography. */
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "geom_wgs", nullable = false)
    private LineString geomWgs;

    @Column(name = "length_m", nullable = false)
    private BigDecimal lengthM;

    @Column(name = "trip_count", nullable = false)
    private int tripCount;

    /** The shape that vehicles on this route and direction are projected onto. */
    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    /** Endpoints meet, so spacing between vehicles wraps past the end of the line. */
    @Column(name = "is_loop", nullable = false)
    private boolean loop;

    protected RouteShapeEntity() {
        // Required by JPA.
    }

    public RouteShapeEntity(String shapeId, Long feedVersionId, String routeId, short directionId, LineString geom, LineString geomWgs, BigDecimal lengthM, int tripCount) {
        this.shapeId = shapeId;
        this.feedVersionId = feedVersionId;
        this.routeId = routeId;
        this.directionId = directionId;
        this.geom = geom;
        this.geomWgs = geomWgs;
        this.lengthM = lengthM;
        this.tripCount = tripCount;
    }

    public String getShapeId() {
        return shapeId;
    }

    public Long getFeedVersionId() {
        return feedVersionId;
    }

    public String getRouteId() {
        return routeId;
    }

    public short getDirectionId() {
        return directionId;
    }

    public LineString getGeom() {
        return geom;
    }

    public LineString getGeomWgs() {
        return geomWgs;
    }

    public BigDecimal getLengthM() {
        return lengthM;
    }

    public int getTripCount() {
        return tripCount;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public boolean isLoop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

}
