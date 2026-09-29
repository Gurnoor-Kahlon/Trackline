package ca.trackline.api.persistence.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A single-date addition to or removal from a service pattern, such as a holiday
 * schedule. Applied on top of {@link CalendarServiceEntity} when resolving which
 * services run on a given date.
 */
@Entity
@Table(name = "calendar_exceptions")
@IdClass(CalendarExceptionEntity.CalendarExceptionId.class)
public class CalendarExceptionEntity {

    /** GTFS exception_type 1: service runs on this date although the pattern excludes it. */
    public static final short SERVICE_ADDED = 1;

    /** GTFS exception_type 2: service does not run on this date although the pattern includes it. */
    public static final short SERVICE_REMOVED = 2;

    @Id
    @Column(name = "service_id", nullable = false)
    private String serviceId;

    @Id
    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    @Column(name = "exception_type", nullable = false)
    private short exceptionType;

    protected CalendarExceptionEntity() {
        // Required by JPA.
    }

    public CalendarExceptionEntity(String serviceId, LocalDate exceptionDate, short exceptionType) {
        this.serviceId = serviceId;
        this.exceptionDate = exceptionDate;
        this.exceptionType = exceptionType;
    }

    public String getServiceId() {
        return serviceId;
    }

    public LocalDate getExceptionDate() {
        return exceptionDate;
    }

    public short getExceptionType() {
        return exceptionType;
    }

    public boolean addsService() {
        return exceptionType == SERVICE_ADDED;
    }

    /** Composite key: one exception per service per date. */
    public record CalendarExceptionId(String serviceId, LocalDate exceptionDate) implements Serializable {

        public CalendarExceptionId {
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(exceptionDate, "exceptionDate");
        }
    }
}
