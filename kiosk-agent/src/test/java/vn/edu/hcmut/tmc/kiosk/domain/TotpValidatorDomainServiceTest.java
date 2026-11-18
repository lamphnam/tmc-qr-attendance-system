package vn.edu.hcmut.tmc.kiosk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.kiosk.domain.service.TotpValidatorDomainService;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.JdkHmacSha256;

class TotpValidatorDomainServiceTest {
    private static final byte[] SECRET = "TMC_LOAD_BD_1000_V1".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @CsvSource({"1,990261", "59830000,727791", "59830001,242014", "4294967301,013711"})
    void agreesWithUserQrAndLoadVectors(long step, String code) {
        assertThat(at(step * 30).match(SECRET, code)).hasValue(step);
    }

    @Test
    void atBoundaryAcceptsOnlyCurrentAndPrevious() {
        long step = 59_830_000;
        assertThat(at(step * 30 + 29).match(SECRET, "727791")).hasValue(step);
        assertThat(at(step * 30 + 29).match(SECRET, "242014")).isEmpty();
        assertThat(at((step + 1) * 30).match(SECRET, "727791")).hasValue(step);
        assertThat(at((step + 1) * 30).match(SECRET, "242014")).hasValue(step + 1);
        assertThat(at((step + 2) * 30).match(SECRET, "727791")).isEmpty();
        assertThat(at(step * 30).match(SECRET, "727790")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abcd12", "01371", "0137111", " 13711"})
    void rejectsMalformedCodes(String code) {
        assertThat(at(4_294_967_301L * 30).match(SECRET, code)).isEmpty();
    }

    private static TotpValidatorDomainService at(long seconds) {
        return new TotpValidatorDomainService(
                Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC), new JdkHmacSha256());
    }
}
