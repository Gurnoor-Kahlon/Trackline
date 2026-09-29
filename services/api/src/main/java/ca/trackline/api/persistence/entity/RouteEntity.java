package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A TTC route. {@code route_id} is the only identifier that reliably joins the
 * realtime feeds to static data, so it anchors the whole model.
 */
@Entity
@Table(name = "routes")
public class RouteEntity {

    @Id
    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "feed_version_id", nullable = false)
    private Long feedVersionId;

    /** Route number as riders know it, for example 501. */
    @Column(name = "short_name", nullable = false)
    private String shortName;

    @Column(name = "long_name", nullable = false)
    private String longName;

    /** GTFS route_type: 0 streetcar, 1 subway, 3 bus. */
    @Column(name = "route_type", nullable = false)
    private short routeType;

    /** Six hex digits, populated by the TTC for every route. */
    @Column(name = "color", length = 6)
    private String color;

    @Column(name = "text_color", length = 6)
    private String textColor;

    /** Derived: the TTC publishes no subway vehicle positions, so subway routes never have live vehicles. */
    @Column(name = "supports_realtime", nullable = false)
    private boolean supportsRealtime;

    protected RouteEntity() {
        // Required by JPA.
    }

    public RouteEntity(String routeId, Long feedVersionId, String shortName, String longName, short routeType, String color, String textColor, boolean supportsRealtime) {
        this.routeId = routeId;
        this.feedVersionId = feedVersionId;
        this.shortName = shortName;
        this.longName = longName;
        this.routeType = routeType;
        this.color = color;
        this.textColor = textColor;
        this.supportsRealtime = supportsRealtime;
    }

    public String getRouteId() {
        return routeId;
    }

    public Long getFeedVersionId() {
        return feedVersionId;
    }

    public String getShortName() {
        return shortName;
    }

    public String getLongName() {
        return longName;
    }

    public short getRouteType() {
        return routeType;
    }

    public String getColor() {
        return color;
    }

    public String getTextColor() {
        return textColor;
    }

    public boolean supportsRealtime() {
        return supportsRealtime;
    }

}
