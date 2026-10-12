package ca.trackline.api.gtfs.realtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import org.springframework.stereotype.Component;

/**
 * Parses a feed response into snapshots, dropping what will not survive use.
 *
 * <p>Every optional field is read through {@code hasX()} rather than defaulted.
 * Protobuf returns zero for an absent number and an empty string for an absent
 * string, so reading without checking would turn "the agency did not say" into
 * "the agency said zero" — which for a speed or a bearing is a fabricated
 * measurement.
 */
@Component
public class RealtimeFeedParser {

    /**
     * @param payload raw protobuf response body
     * @param validator rules to apply, built from the active schedule
     * @param now the current time, for ageing checks
     * @throws GtfsRealtimeFormatException if the payload is not a GTFS-Realtime message
     */
    public FeedSnapshot parse(RealtimeFeed feed, byte[] payload, FeedEntityValidator validator, Instant now) {
        FeedMessage message = readMessage(payload);
        Map<RejectionReason, Integer> rejections = new EnumMap<>(RejectionReason.class);

        List<VehicleSnapshot> vehicles = new ArrayList<>();
        List<TripUpdateSnapshot> tripUpdates = new ArrayList<>();
        List<AlertSnapshot> alerts = new ArrayList<>();

        for (FeedEntity entity : message.getEntityList()) {
            if (entity.getIsDeleted()) {
                continue;
            }
            switch (feed) {
                case VEHICLE_POSITIONS -> {
                    if (entity.hasVehicle()) {
                        readVehicle(entity.getVehicle(), validator, now, vehicles, rejections);
                    }
                }
                case TRIP_UPDATES -> {
                    if (entity.hasTripUpdate()) {
                        readTripUpdate(entity.getTripUpdate(), validator, tripUpdates, rejections);
                    }
                }
                case ALERTS -> {
                    if (entity.hasAlert()) {
                        readAlert(entity.getId(), entity.getAlert(), validator, alerts, rejections);
                    }
                }
            }
        }

        Instant headerTimestamp = message.getHeader().hasTimestamp()
                ? Instant.ofEpochSecond(message.getHeader().getTimestamp())
                : null;

        return new FeedSnapshot(
                feed, headerTimestamp, List.copyOf(vehicles), List.copyOf(tripUpdates),
                List.copyOf(alerts), Map.copyOf(rejections), message.getEntityCount());
    }

    private static FeedMessage readMessage(byte[] payload) {
        try {
            return FeedMessage.parseFrom(payload);
        } catch (InvalidProtocolBufferException cause) {
            // A partially parsed FULL_DATASET is not trustworthy, so the whole cycle
            // is rejected and the previous snapshot kept.
            throw new GtfsRealtimeFormatException("Response is not a GTFS-Realtime message", cause);
        }
    }

    private static void readVehicle(
            VehiclePosition published,
            FeedEntityValidator validator,
            Instant now,
            List<VehicleSnapshot> accepted,
            Map<RejectionReason, Integer> rejections) {

        if (!published.hasPosition()) {
            count(rejections, RejectionReason.MISSING_POSITION);
            return;
        }

        Position position = published.getPosition();
        TripDescriptor trip = published.getTrip();
        VehicleDescriptor vehicle = published.getVehicle();

        VehicleSnapshot snapshot = new VehicleSnapshot(
                vehicle.hasId() ? vehicle.getId() : null,
                trip.hasRouteId() ? trip.getRouteId() : null,
                trip.hasTripId() ? trip.getTripId() : null,
                position.getLatitude(),
                position.getLongitude(),
                position.hasBearing() ? position.getBearing() : null,
                position.hasSpeed() ? position.getSpeed() : null,
                published.hasCurrentStatus() ? (short) published.getCurrentStatus().getNumber() : null,
                published.hasCurrentStopSequence() ? published.getCurrentStopSequence() : null,
                published.hasStopId() ? published.getStopId() : null,
                published.hasOccupancyStatus() ? (short) published.getOccupancyStatus().getNumber() : null,
                published.hasTimestamp() ? Instant.ofEpochSecond(published.getTimestamp()) : null);

        validator.rejectVehicle(snapshot, now)
                .ifPresentOrElse(reason -> count(rejections, reason), () -> accepted.add(snapshot));
    }

    private static void readTripUpdate(
            TripUpdate published,
            FeedEntityValidator validator,
            List<TripUpdateSnapshot> accepted,
            Map<RejectionReason, Integer> rejections) {

        List<TripUpdateSnapshot.PredictedCall> calls = new ArrayList<>(published.getStopTimeUpdateCount());
        for (StopTimeUpdate update : published.getStopTimeUpdateList()) {
            calls.add(new TripUpdateSnapshot.PredictedCall(
                    update.hasStopSequence() ? update.getStopSequence() : -1,
                    update.hasStopId() ? update.getStopId() : null,
                    update.hasArrival() && update.getArrival().hasTime()
                            ? Instant.ofEpochSecond(update.getArrival().getTime())
                            : null,
                    update.hasDeparture() && update.getDeparture().hasTime()
                            ? Instant.ofEpochSecond(update.getDeparture().getTime())
                            : null));
        }

        TripDescriptor trip = published.getTrip();
        TripUpdateSnapshot snapshot = new TripUpdateSnapshot(
                trip.hasTripId() ? trip.getTripId() : null,
                trip.hasRouteId() ? trip.getRouteId() : null,
                published.hasVehicle() && published.getVehicle().hasId() ? published.getVehicle().getId() : null,
                List.copyOf(calls),
                published.hasTimestamp() ? Instant.ofEpochSecond(published.getTimestamp()) : null);

        validator.rejectTripUpdate(snapshot)
                .ifPresentOrElse(reason -> count(rejections, reason), () -> accepted.add(snapshot));
    }

    /** Bounds on untrusted alert text. The longest real description measured was 233 characters. */
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_HEADER_LENGTH = 300;
    private static final int MAX_DESCRIPTION_LENGTH = 4_000;
    private static final int MAX_URL_LENGTH = 2_048;

    private static void readAlert(
            String entityId,
            Alert published,
            FeedEntityValidator validator,
            List<AlertSnapshot> accepted,
            Map<RejectionReason, Integer> rejections) {

        List<AlertSnapshot.AffectedEntity> affected = new ArrayList<>(published.getInformedEntityCount());
        for (EntitySelector selector : published.getInformedEntityList()) {
            affected.add(new AlertSnapshot.AffectedEntity(
                    selector.hasRouteId() ? clean(selector.getRouteId(), MAX_ID_LENGTH) : null,
                    selector.hasStopId() ? clean(selector.getStopId(), MAX_ID_LENGTH) : null));
        }

        AlertSnapshot snapshot = new AlertSnapshot(
                clean(entityId, MAX_ID_LENGTH),
                published.hasCause() ? published.getCause().getNumber() : null,
                published.hasEffect() ? published.getEffect().getNumber() : null,
                firstTranslation(published.hasHeaderText() ? published.getHeaderText() : null, MAX_HEADER_LENGTH),
                firstTranslation(
                        published.hasDescriptionText() ? published.getDescriptionText() : null,
                        MAX_DESCRIPTION_LENGTH),
                firstTranslation(published.hasUrl() ? published.getUrl() : null, MAX_URL_LENGTH),
                List.copyOf(affected));

        validator.rejectAlert(snapshot)
                .ifPresentOrElse(reason -> count(rejections, reason), () -> accepted.add(snapshot));
    }

    /**
     * The first translation of a translated string. The TTC publishes one language,
     * so picking the first is the whole of the language handling this needs.
     */
    private static String firstTranslation(
            com.google.transit.realtime.GtfsRealtime.TranslatedString translated, int maxLength) {
        if (translated == null || translated.getTranslationCount() == 0) {
            return null;
        }
        String text = clean(translated.getTranslation(0).getText(), maxLength);
        return text.isBlank() ? null : text;
    }

    /**
     * Makes a published string safe to store. The text is untrusted: a NUL character
     * is not storable in PostgreSQL and would fail every write, and every cycle after
     * it, for as long as the alert stayed in the feed; and nothing else bounds its size.
     * Real values are far inside these limits (the longest alert text measured, on
     * 2026-10-12, was 233 characters), so truncation is a defence and not a routine event.
     */
    static String clean(String text, int maxLength) {
        String printable = text.indexOf('\0') >= 0 ? text.replace("\0", "") : text;
        if (printable.codePointCount(0, printable.length()) <= maxLength) {
            return printable;
        }
        // The limit is in characters, so a character that takes two UTF-16 units is never split.
        return printable.substring(0, printable.offsetByCodePoints(0, maxLength));
    }

    private static void count(Map<RejectionReason, Integer> rejections, RejectionReason reason) {
        rejections.merge(reason, 1, Integer::sum);
    }
}
