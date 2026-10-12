package ca.trackline.api.common.geo;

import ca.trackline.api.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundingBoxTest {

    @Test
    void parsesFourCornersInTheDocumentedOrder() {
        BoundingBox box = BoundingBox.parse("-79.5,43.6,-79.3,43.8");

        assertThat(box.minLongitude()).isEqualTo(-79.5);
        assertThat(box.minLatitude()).isEqualTo(43.6);
        assertThat(box.maxLongitude()).isEqualTo(-79.3);
        assertThat(box.maxLatitude()).isEqualTo(43.8);
    }

    @Test
    void rejectsCornersTheWrongWayRound() {
        assertThatThrownBy(() -> BoundingBox.parse("-79.3,43.6,-79.5,43.8"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("wrong order");
        assertThatThrownBy(() -> BoundingBox.parse("-79.5,43.8,-79.3,43.6"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("wrong order");
    }

    @Test
    void rejectsABoxLargerThanOneDegree() {
        assertThatThrownBy(() -> BoundingBox.parse("-80.5,43.0,-79.3,43.5"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("1.0 degrees");
        assertThatThrownBy(() -> BoundingBox.parse("-79.9,43.0,-79.3,44.1"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aBoxExactlyOneDegreeAcrossIsAllowed() {
        assertThat(BoundingBox.parse("-80.0,43.0,-79.0,44.0")).isNotNull();
    }

    @Test
    void edgesAreInclusive() {
        BoundingBox box = BoundingBox.parse("-79.5,43.6,-79.3,43.8");

        assertThat(box.contains(-79.5, 43.6)).isTrue();
        assertThat(box.contains(-79.3, 43.8)).isTrue();
        assertThat(box.contains(-79.4, 43.7)).isTrue();
        assertThat(box.contains(-79.51, 43.7)).isFalse();
        assertThat(box.contains(-79.4, 43.81)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-79.5,43.6,-79.3,43.8", "0,0,1,1", "-1.5,-2.5,-0.5,-1.5"})
    void theFormatAcceptsFourDecimals(String text) {
        assertThat(text).matches(BoundingBox.FORMAT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1,2,3", "1,2,3,4,5", "a,b,c,d", "1,2,3,", "1;2;3;4", ".5,1,2,3", "1,2,3,4 "})
    void theFormatRefusesAnythingElse(String text) {
        assertThat(text).doesNotMatch(BoundingBox.FORMAT);
    }
}
