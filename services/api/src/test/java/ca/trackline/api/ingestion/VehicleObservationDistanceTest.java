package ca.trackline.api.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distance behind the deduplication threshold.
 *
 * <p>Worth testing directly because it is an approximation and because the
 * threshold it feeds is expressed in metres. If this were out by the cosine factor
 * it would still look like a plausible number, and vehicles would be recorded
 * either far too often or not often enough with nothing obviously wrong.
 */
class VehicleObservationDistanceTest {

    /** Toronto City Hall. */
    private static final double LATITUDE = 43.6534;
    private static final double LONGITUDE = -79.3841;

    @Test
    void identicalPointsAreZeroApart() {
        assertThat(VehicleObservationWriter.metresBetween(LATITUDE, LONGITUDE, LATITUDE, LONGITUDE))
                .isZero();
    }

    @Test
    void aKnownNorthwardStepMeasuresInMetres() {
        // A thousandth of a degree of latitude is about 111 m everywhere.
        double metres = VehicleObservationWriter.metresBetween(
                LATITUDE, LONGITUDE, LATITUDE + 0.001, LONGITUDE);

        assertThat(metres).isCloseTo(111.3, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void eastwardDistanceIsNarrowedByLatitude() {
        // A degree of longitude is shorter the further from the equator: at 43.65
        // degrees north it is about cos(43.65) = 0.72 of a degree of latitude.
        // Omitting that factor is the classic way this calculation goes wrong, and
        // it would overstate east-west movement by nearly 40%.
        double eastward = VehicleObservationWriter.metresBetween(
                LATITUDE, LONGITUDE, LATITUDE, LONGITUDE + 0.001);

        assertThat(eastward).isCloseTo(80.6, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void theThresholdDistanceIsRecognisablyShort() {
        // 25 m is the configured movement that always earns a stored observation:
        // about a bus length and a half, and clearly distinct from a stationary
        // vehicle's GPS jitter.
        double jitter = VehicleObservationWriter.metresBetween(
                LATITUDE, LONGITUDE, LATITUDE + 0.00005, LONGITUDE + 0.00005);

        assertThat(jitter).isLessThan(25);
    }

    @Test
    void distanceIsSymmetric() {
        double there = VehicleObservationWriter.metresBetween(43.6534, -79.3841, 43.7000, -79.4000);
        double back = VehicleObservationWriter.metresBetween(43.7000, -79.4000, 43.6534, -79.3841);

        assertThat(there).isEqualTo(back);
    }
}
