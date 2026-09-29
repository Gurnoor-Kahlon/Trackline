package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

/**
 * A group of stops presented as one place.
 *
 * <p>Derived by Trackline rather than supplied by the feed: the TTC GTFS has no
 * station hierarchy at all. {@link #getDerivationMethod()} records how the
 * grouping was produced, so the UI can describe these as grouped stops honestly.
 */
@Entity
@Table(name = "stations")
public class StationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "centroid", nullable = false)
    private Point centroid;

    @Column(name = "stop_count", nullable = false)
    private int stopCount;

    @Column(name = "derivation_method", nullable = false)
    private String derivationMethod;

    protected StationEntity() {
        // Required by JPA.
    }

    public StationEntity(String name, Point centroid, int stopCount, String derivationMethod) {
        this.name = name;
        this.centroid = centroid;
        this.stopCount = stopCount;
        this.derivationMethod = derivationMethod;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Point getCentroid() {
        return centroid;
    }

    public int getStopCount() {
        return stopCount;
    }

    public String getDerivationMethod() {
        return derivationMethod;
    }

}
