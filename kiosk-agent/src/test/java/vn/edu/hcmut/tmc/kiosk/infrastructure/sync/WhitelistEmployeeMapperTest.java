package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;

@ExtendWith(OutputCaptureExtension.class)
class WhitelistEmployeeMapperTest {

    private final WhitelistEmployeeMapper mapper = new WhitelistEmployeeMapper();

    private static WhitelistEmployeeWireDto row(String id, String status, String secret) {
        return new WhitelistEmployeeWireDto(id, "Synthetic employee", "BD", "Assembly", secret, status,
                7, Instant.parse("2026-11-14T00:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "REVOKED"})
    void mapsStatusIdVersionAndExactUtf8WithoutTrimmingOrDecoding(String status, CapturedOutput output) {
        String secret = "  material thử nghiệm Đ-ß-🔐  ";
        WhitelistEmployeeWireDto wire = row(" EMP-001 ", status, secret);

        WhitelistEmployee employee = mapper.mapEmployees(List.of(wire)).getFirst();

        assertThat(employee.employeeId()).isEqualTo("EMP-001");
        assertThat(employee.qrStatus()).isEqualTo(QrStatus.valueOf(status));
        assertThat(employee.secretVersion()).isEqualTo(7);
        assertThat(employee.secretMaterial()).containsExactly(secret.getBytes(StandardCharsets.UTF_8));
        assertThat(wire.sharedSecret()).isEqualTo(secret);
        assertThat(wire.toString()).doesNotContain(secret);
        assertThat(employee.toString()).doesNotContain(secret, Arrays.toString(secret.getBytes(StandardCharsets.UTF_8)));
        assertThat(output).doesNotContain(secret);
    }

    @Test
    void base64LookingMaterialIsEncodedAsTextAndNeverDecoded() {
        byte[] material = mapper.mapEmployees(List.of(row("EMP-001", "ACTIVE", "YWJjZA=="))).getFirst().secretMaterial();
        assertThat(material).containsExactly("YWJjZA==".getBytes(StandardCharsets.UTF_8));
        assertThat(Arrays.equals(material, "abcd".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void resultAndMaterialStayImmutableWhenInputsOrAccessorCopiesAreChanged() {
        String secret = "synthetic-verification-material";
        List<WhitelistEmployeeWireDto> input = new ArrayList<>(List.of(row("EMP-001", "ACTIVE", secret)));
        List<WhitelistEmployee> result = mapper.mapEmployees(input);
        input.clear();

        byte[] copy = result.getFirst().secretMaterial();
        Arrays.fill(copy, (byte) 0);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().secretMaterial()).containsExactly(secret.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyListIsOnlyAnEmptyEmployeeProjectionWithoutAnyMetadata() {
        assertThat(mapper.mapEmployees(List.of())).isEmpty();
    }

    @Test
    void rejectsNullListNullRowsAndDuplicateNormalizedIds() {
        assertInvalid(() -> mapper.mapEmployees(null));
        assertInvalid(() -> mapper.mapEmployees(Arrays.asList(row("EMP-001", "ACTIVE", "synthetic"), null)));
        assertInvalid(() -> mapper.mapEmployees(List.of(row("EMP-001", "ACTIVE", "synthetic"),
                row(" EMP-001 ", "REVOKED", "synthetic-other"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"high", "low"})
    void rejectsUnpairedSurrogatesInsteadOfSilentlyChangingVerificationMaterial(String surrogate, CapturedOutput output) {
        String secret = "synthetic-sensitive-" + (surrogate.equals("high") ? '\uD800' : '\uDC00');
        assertInvalid(() -> mapper.mapEmployees(List.of(row("EMP-001", "ACTIVE", secret))));
        assertThat(output).doesNotContain("synthetic-sensitive-");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void wireBoundaryRejectsAbsentOrBlankMaterialBeforeMapping(String secret) {
        assertThatThrownBy(() -> mapper.mapEmployees(List.of(row("EMP-001", "ACTIVE", secret))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid whitelist employee response").hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"DISABLED", "UNKNOWN", "active"})
    void wireBoundaryRejectsStatusesNotSupportedByTheCurrentCentralContract(String status) {
        assertThatThrownBy(() -> mapper.mapEmployees(List.of(row("EMP-001", status, "synthetic"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid whitelist employee response").hasNoCause();
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid whitelist employee mapping input").hasNoCause();
    }
}
