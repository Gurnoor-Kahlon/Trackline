package ca.trackline.api.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * A GTFS service pattern: which weekdays it runs, over what date range.
 * Answering what is scheduled right now needs this together with
 * {@link CalendarExceptionEntity}.
 */
@Entity
@Table(name = "calendar_services")
public class CalendarServiceEntity {

    @Id
    @Column(name = "service_id", nullable = false)
    private String serviceId;

    @Column(name = "feed_version_id", nullable = false)
    private Long feedVersionId;

    @Column(name = "monday", nullable = false)
    private boolean monday;

    @Column(name = "tuesday", nullable = false)
    private boolean tuesday;

    @Column(name = "wednesday", nullable = false)
    private boolean wednesday;

    @Column(name = "thursday", nullable = false)
    private boolean thursday;

    @Column(name = "friday", nullable = false)
    private boolean friday;

    @Column(name = "saturday", nullable = false)
    private boolean saturday;

    @Column(name = "sunday", nullable = false)
    private boolean sunday;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    protected CalendarServiceEntity() {
        // Required by JPA.
    }

    public CalendarServiceEntity(String serviceId, Long feedVersionId, boolean monday, boolean tuesday, boolean wednesday, boolean thursday, boolean friday, boolean saturday, boolean sunday, LocalDate startDate, LocalDate endDate) {
        this.serviceId = serviceId;
        this.feedVersionId = feedVersionId;
        this.monday = monday;
        this.tuesday = tuesday;
        this.wednesday = wednesday;
        this.thursday = thursday;
        this.friday = friday;
        this.saturday = saturday;
        this.sunday = sunday;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public String getServiceId() {
        return serviceId;
    }

    public Long getFeedVersionId() {
        return feedVersionId;
    }

    public boolean runsOnMonday() {
        return monday;
    }

    public boolean runsOnTuesday() {
        return tuesday;
    }

    public boolean runsOnWednesday() {
        return wednesday;
    }

    public boolean runsOnThursday() {
        return thursday;
    }

    public boolean runsOnFriday() {
        return friday;
    }

    public boolean runsOnSaturday() {
        return saturday;
    }

    public boolean runsOnSunday() {
        return sunday;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

}
