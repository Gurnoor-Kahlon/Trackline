package ca.trackline.api.auth;

import ca.trackline.api.common.error.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Passwords are stored only as BCrypt hashes, and a password has to be one BCrypt can read in full.
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(AuthFixtures.properties());

    @Test
    void aHashIsBcryptAtTheConfiguredCostAndIsNotThePassword() {
        String hash = hasher.hash("correct horse battery");

        // $2a$ or $2b$, then the cost the settings asked for.
        assertThat(hash).matches("^\\$2[aby]\\$04\\$.{53}$");
        assertThat(hash).doesNotContain("correct horse battery");
    }

    @Test
    void theSamePasswordHashesDifferentlyEachTimeSoACopyOfTheTableGivesNoShortcut() {
        assertThat(hasher.hash("correct horse battery")).isNotEqualTo(hasher.hash("correct horse battery"));
    }

    @Test
    void aHashChecksOutOnlyForThePasswordItWasMadeFrom() {
        String hash = hasher.hash("correct horse battery");

        assertThat(hasher.matches("correct horse battery", hash)).isTrue();
        assertThat(hasher.matches("correct horse batterY", hash)).isFalse();
        assertThat(hasher.matches("", hash)).isFalse();
    }

    @Test
    void theCostIsWhateverTheSettingsSayAndEachHashRecordsIt() {
        AuthProperties cost6 = new AuthProperties(
                AuthFixtures.SECRET, "trackline", java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(30),
                java.time.Duration.ofSeconds(10), 6, 10, 5, java.time.Duration.ofMinutes(15));

        assertThat(new PasswordHasher(cost6).hash("correct horse battery")).startsWith("$2a$06$");
    }

    @Test
    void aPasswordOfTheMinimumLengthIsAcceptedAndOneCharacterShorterIsNot() {
        assertThatCode(() -> hasher.requireAcceptable("a".repeat(10))).doesNotThrowAnyException();
        assertThatThrownBy(() -> hasher.requireAcceptable("a".repeat(9)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at least 10 characters");
    }

    @Test
    void lengthIsCountedInCharactersNotInUtf16Units() {
        // Five emoji are ten UTF-16 units and five characters: short by the rule, long by a careless count.
        assertThatThrownBy(() -> hasher.requireAcceptable("😀".repeat(5))).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> hasher.requireAcceptable("😀".repeat(10))).doesNotThrowAnyException();
    }

    @Test
    void aPasswordOfSeventyTwoBytesIsAcceptedAndSeventyThreeIsRefusedNotSilentlyCut() {
        assertThatCode(() -> hasher.requireAcceptable("a".repeat(72))).doesNotThrowAnyException();
        assertThatThrownBy(() -> hasher.requireAcceptable("a".repeat(73)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 72 bytes");
    }

    @Test
    void theLimitIsInBytesSoTwoByteCharactersReachItSooner() {
        // 37 two-byte characters are 74 bytes, though only 37 characters.
        assertThatThrownBy(() -> hasher.requireAcceptable("é".repeat(37))).isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> hasher.requireAcceptable("é".repeat(36))).doesNotThrowAnyException();
    }

    @Test
    void aMissingPasswordIsRefused() {
        assertThatThrownBy(() -> hasher.requireAcceptable(null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void spendingTheTimeOfACheckGivesNothingAwayAndTouchesNoAccount() {
        assertThatCode(() -> hasher.spendTimeOfOneCheck("whatever was typed")).doesNotThrowAnyException();
    }
}
