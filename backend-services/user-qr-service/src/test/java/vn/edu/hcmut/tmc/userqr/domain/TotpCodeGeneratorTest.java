package vn.edu.hcmut.tmc.userqr.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.edu.hcmut.tmc.userqr.domain.service.TotpCodeGenerator;

class TotpCodeGeneratorTest {
    private final TotpCodeGenerator generator = new TotpCodeGenerator();
    private static final String RFC_SHA256_KEY = "12345678901234567890123456789012";

    // RFC 6238 Appendix B SHA256 vectors reduced from eight to six decimal digits.
    @ParameterizedTest
    @CsvSource({"59,119246", "1111111109,084774", "1111111111,062674", "1234567890,819424",
            "2000000000,698825", "20000000000,737706"})
    void matchesIndependentRfcVectors(long second, String expected) {
        assertThat(generator.generateTotp(RFC_SHA256_KEY, Instant.ofEpochSecond(second))).isEqualTo(expected);
    }

    @Test
    void deterministicWithinStepAndChangesAtExactBoundary() {
        assertThat(generator.generateTotp(RFC_SHA256_KEY, Instant.ofEpochSecond(30)))
                .isEqualTo(generator.generateTotp(RFC_SHA256_KEY, Instant.ofEpochSecond(59, 999_999_999)));
        assertThat(generator.generateTotp(RFC_SHA256_KEY, Instant.ofEpochSecond(60)))
                .isNotEqualTo(generator.generateTotp(RFC_SHA256_KEY, Instant.ofEpochSecond(59)));
    }

    @Test
    void acceptsOnlyCurrentAndPreviousStep() {
        Instant now = Instant.ofEpochSecond(120);
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, generator.generateTotp(RFC_SHA256_KEY, now), now)).isTrue();
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, generator.generateTotp(RFC_SHA256_KEY, now.minusSeconds(30)), now)).isTrue();
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, generator.generateTotp(RFC_SHA256_KEY, now.minusSeconds(60)), now)).isFalse();
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, generator.generateTotp(RFC_SHA256_KEY, now.plusSeconds(30)), now)).isFalse();
    }

    @Test
    void changedSecretRejectsOldFixtureCode() {
        Instant now = Instant.ofEpochSecond(120);
        String old = generator.generateTotp(RFC_SHA256_KEY, now);
        assertThat(generator.verifyTotpWithWindow("different-secure-secret", old, now)).isFalse();
    }

    @Test
    void rejectsMalformedCodesAndHandlesEpochWithoutNegativePreviousStep() {
        for (String code : new String[]{null, "", "12345", "1234567", "abcdef", "１２３４５６", " 123456"}) {
            assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, code, Instant.EPOCH)).isFalse();
        }
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY,
                generator.generateTotp(RFC_SHA256_KEY, Instant.EPOCH), Instant.EPOCH)).isTrue();
        assertThat(generator.verifyTotpWithWindow(RFC_SHA256_KEY, "123456", Instant.ofEpochSecond(-1))).isFalse();
        assertThatThrownBy(() -> generator.generateTotp("", Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
