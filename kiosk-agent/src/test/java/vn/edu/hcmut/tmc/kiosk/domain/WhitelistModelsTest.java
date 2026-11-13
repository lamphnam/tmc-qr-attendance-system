package vn.edu.hcmut.tmc.kiosk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistLookup;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;

class WhitelistModelsTest {

    private static final Instant CACHED_AT = Instant.parse("2026-11-14T01:00:00.123Z");

    private static byte[] syntheticMaterial() {
        return "synthetic-material-for-unit-test".getBytes(StandardCharsets.UTF_8);
    }

    private static WhitelistEmployee employee(String id) {
        return new WhitelistEmployee(id, QrStatus.ACTIVE, 1, syntheticMaterial());
    }

    private static WhitelistMetadata metadata(int count) {
        return new WhitelistMetadata("BD-K01", "BD", 3, CACHED_AT, count);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\r\n", "\u2003"})
    void rejectsMissingOrBlankEmployeeIdsWithoutEchoingInput(String id) {
        assertThatThrownBy(() -> employee(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("employeeId must not be blank");
    }

    @Test
    void normalizesSurroundingWhitespaceWithoutChangingIdCase() {
        assertThat(employee("  Emp001\t").employeeId()).isEqualTo("Emp001");
    }

    @Test
    void rejectsMissingStatusAndInvalidSecretVersions() {
        assertThatThrownBy(() -> new WhitelistEmployee("EMP001", null, 1, syntheticMaterial()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("qrStatus must not be null");
        for (int version : new int[] {0, -1}) {
            assertThatThrownBy(() -> new WhitelistEmployee("EMP001", QrStatus.ACTIVE, version, syntheticMaterial()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("secretVersion must be positive");
        }
    }

    @ParameterizedTest
    @EnumSource(QrStatus.class)
    void preservesStatusAndRepresentsUnavailableMaterialWithoutAuthorizingAnEmployee(QrStatus status) {
        WhitelistEmployee employee = new WhitelistEmployee("EMP001", status, 1, null);

        assertThat(employee.qrStatus()).isEqualTo(status);
        assertThat(employee.secretMaterial()).isNull();
        assertThat(employee.toString()).contains("secretMaterial=<redacted>");
    }

    @Test
    void rejectsAnEmptyMaterialArray() {
        assertThatThrownBy(() -> new WhitelistEmployee("EMP001", QrStatus.ACTIVE, 1, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("secretMaterial must not be empty when present");
    }

    @Test
    void copiesMaterialOnConstructionAndOnEveryRead() {
        byte[] input = syntheticMaterial();
        byte[] expected = input.clone();
        WhitelistEmployee employee = new WhitelistEmployee("EMP001", QrStatus.ACTIVE, 1, input);

        Arrays.fill(input, (byte) 0);
        byte[] firstRead = employee.secretMaterial();
        assertThat(firstRead).containsExactly(expected);
        Arrays.fill(firstRead, (byte) 1);

        assertThat(employee.secretMaterial()).containsExactly(expected).isNotSameAs(firstRead);
    }

    @Test
    void equalityAndHashCodeUseMaterialContentsRatherThanArrayIdentity() {
        WhitelistEmployee first = employee("EMP001");
        WhitelistEmployee same = employee("EMP001");

        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        Arrays.fill(first.secretMaterial(), (byte) 0);
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(first).isNotEqualTo(new WhitelistEmployee("EMP001", QrStatus.ACTIVE, 1, new byte[] {7}));
        assertThat(first).isNotEqualTo(employee("EMP002"));
        assertThat(first).isNotEqualTo(new WhitelistEmployee("EMP001", QrStatus.REVOKED, 1, syntheticMaterial()));
        assertThat(first).isNotEqualTo(new WhitelistEmployee("EMP001", QrStatus.ACTIVE, 2, syntheticMaterial()));
        assertThat(new WhitelistEmployee("EMP001", QrStatus.DISABLED, 1, null))
                .isEqualTo(new WhitelistEmployee("EMP001", QrStatus.DISABLED, 1, null));
    }

    @Test
    void rejectsDuplicateEmployeeIdsEvenAfterNormalization() {
        assertThatThrownBy(() -> new WhitelistSnapshot(metadata(2), List.of(employee("EMP001"), employee(" EMP001 "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("employees must have unique employeeId values");
    }

    @Test
    void copiesTheEmployeeListAndExposesAnUnmodifiableList() {
        List<WhitelistEmployee> input = new ArrayList<>(List.of(employee("EMP001")));
        WhitelistSnapshot snapshot = new WhitelistSnapshot(metadata(1), input);

        input.clear();

        assertThat(snapshot.employees()).containsExactly(employee("EMP001"));
        assertThatThrownBy(() -> snapshot.employees().add(employee("EMP002")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.employees().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingSnapshotPartsAndNullEmployeeItems() {
        assertThatThrownBy(() -> new WhitelistSnapshot(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("metadata must not be null");
        assertThatThrownBy(() -> new WhitelistSnapshot(metadata(0), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employees must not be null");
        assertThatThrownBy(() -> new WhitelistSnapshot(metadata(1), Arrays.asList((WhitelistEmployee) null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employees must not contain null");
    }

    @Test
    void rejectsMetadataThatDisagreesWithTheSnapshotSize() {
        assertThatThrownBy(() -> new WhitelistSnapshot(metadata(0), List.of(employee("EMP001"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employeeCount must match the snapshot size");
        assertThatThrownBy(() -> new WhitelistSnapshot(metadata(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employeeCount must match the snapshot size");
    }

    @Test
    void doesNotExposeMaterialThroughEmployeeSnapshotOrLookupStrings() {
        WhitelistEmployee employee = employee("EMP001");
        List<String> descriptions = List.of(employee.toString(),
                new WhitelistSnapshot(metadata(1), List.of(employee)).toString(),
                WhitelistLookup.found(metadata(1), employee).toString());

        for (String description : descriptions) {
            assertThat(description)
                    .doesNotContain(new String(syntheticMaterial(), StandardCharsets.UTF_8))
                    .doesNotContain(Arrays.toString(syntheticMaterial()))
                    .doesNotContain("[B@");
        }
        assertThat(employee.toString()).contains("secretMaterial=<redacted>");
    }

    @Test
    void rejectsMissingOrBlankMetadataScope() {
        for (String scope : Arrays.asList(null, "", "\t")) {
            assertThatThrownBy(() -> new WhitelistMetadata(scope, "BD", 0, CACHED_AT, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("kioskId must not be blank");
            assertThatThrownBy(() -> new WhitelistMetadata("BD-K01", scope, 0, CACHED_AT, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("factoryId must not be blank");
        }
    }

    @Test
    void rejectsNegativeMetadataVersionCountAndMissingTimestamp() {
        assertThatThrownBy(() -> new WhitelistMetadata("BD-K01", "BD", -1, CACHED_AT, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("version must not be negative");
        assertThatThrownBy(() -> new WhitelistMetadata("BD-K01", "BD", 0, CACHED_AT, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("employeeCount must not be negative");
        assertThatThrownBy(() -> new WhitelistMetadata("BD-K01", "BD", 0, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("cachedAt must not be null");
    }

    @Test
    void normalizesMetadataScopeAndTimestampWithoutInventingExpiry() {
        WhitelistMetadata metadata = new WhitelistMetadata(" BD-K01 ", " BD ", Long.MAX_VALUE,
                Instant.parse("2026-11-14T01:00:00.123456789Z"), 0);

        assertThat(metadata.kioskId()).isEqualTo("BD-K01");
        assertThat(metadata.factoryId()).isEqualTo("BD");
        assertThat(metadata.version()).isEqualTo(Long.MAX_VALUE);
        assertThat(metadata.cachedAt()).isEqualTo(CACHED_AT);
        assertThat(new WhitelistMetadata("BD-K01", "BD", 0, Instant.EPOCH, 0).version()).isZero();
    }

    @Test
    void distinguishesAnUninitializedCacheFromAValidEmptySnapshotIncludingVersionZero() {
        WhitelistMetadata metadata = new WhitelistMetadata("BD-K01", "BD", 0, CACHED_AT, 0);
        WhitelistSnapshot emptySnapshot = new WhitelistSnapshot(metadata, List.of());
        WhitelistLookup uninitialized = WhitelistLookup.uninitialized();
        WhitelistLookup emptyLookup = WhitelistLookup.notFound(emptySnapshot.metadata());

        assertThat(uninitialized.isInitialized()).isFalse();
        assertThat(uninitialized.metadata()).isEmpty();
        assertThat(uninitialized.employee()).isEmpty();
        assertThat(emptyLookup.isInitialized()).isTrue();
        assertThat(emptyLookup.metadata()).contains(metadata);
        assertThat(emptyLookup.employee()).isEmpty();
        assertThat(emptyLookup).isNotEqualTo(uninitialized);
    }

    @Test
    void representsFoundAndMissingEmployeesWithinAnInitializedSnapshot() {
        WhitelistEmployee employee = employee("EMP001");
        WhitelistLookup found = WhitelistLookup.found(metadata(1), employee);
        WhitelistLookup missing = WhitelistLookup.notFound(metadata(1));

        assertThat(found.isInitialized()).isTrue();
        assertThat(found.employee()).contains(employee);
        assertThat(found.metadata()).contains(metadata(1));
        assertThat(missing.isInitialized()).isTrue();
        assertThat(missing.employee()).isEmpty();
    }

    @Test
    void rejectsAnEmployeeWithoutAnInitializedNonEmptySnapshot() {
        assertThatThrownBy(() -> new WhitelistLookup(Optional.empty(), Optional.of(employee("EMP001"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhitelistLookup.found(metadata(0), employee("EMP001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullLookupPartsInsteadOfTreatingThemAsCacheMisses() {
        assertThatThrownBy(() -> new WhitelistLookup(null, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WhitelistLookup(Optional.empty(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhitelistLookup.notFound(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhitelistLookup.found(null, employee("EMP001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WhitelistLookup.found(metadata(1), null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(WhitelistCacheException.Reason.class)
    void cacheFailuresCarryAReasonAndFixedDiagnosticText(WhitelistCacheException.Reason reason) {
        WhitelistCacheException failure = new WhitelistCacheException(reason);

        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getMessage()).isIn("whitelist cache unavailable", "whitelist cache is inconsistent");
        assertThat(failure.getCause()).isNull();
    }
}
