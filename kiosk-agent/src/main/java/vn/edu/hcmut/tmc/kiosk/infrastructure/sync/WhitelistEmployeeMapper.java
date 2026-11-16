package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;

/** Maps employee material only. It neither fetches data nor creates revision/scope metadata or writes a cache. */
public final class WhitelistEmployeeMapper {

    /**
     * Maps the entire validated wire list to immutable domain employees. The HTTP DTO rejects absent/blank
     * secrets and unsupported wire statuses; null rows, duplicate normalized IDs and malformed Unicode are also
     * rejected here. Material is the exact UTF-8 encoding of the String, never a Base32/Base64 decoding.
     *
     * <p>Factory and kiosk authorization remain outside this field mapping. Its result is not a source snapshot.
     *
     * @throws IllegalArgumentException with fixed diagnostics and no cause if the input cannot be mapped
     */
    public List<WhitelistEmployee> mapEmployees(List<WhitelistEmployeeWireDto> rows) {
        if (rows == null) {
            throw invalidInput();
        }
        List<WhitelistEmployee> employees = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (WhitelistEmployeeWireDto row : rows) {
            if (row == null) {
                throw invalidInput();
            }
            WhitelistEmployee employee = mapEmployee(row);
            if (!ids.add(employee.employeeId())) {
                throw invalidInput();
            }
            employees.add(employee);
        }
        return List.copyOf(employees);
    }

    private static WhitelistEmployee mapEmployee(WhitelistEmployeeWireDto row) {
        ByteBuffer encoded = null;
        byte[] material = null;
        try {
            QrStatus status = switch (row.qrStatus()) {
                case "ACTIVE" -> QrStatus.ACTIVE;
                case "REVOKED" -> QrStatus.REVOKED;
                default -> throw invalidInput();
            };
            // Reject malformed Unicode rather than silently replacing characters in verification material.
            encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(row.sharedSecret()));
            material = new byte[encoded.remaining()];
            encoded.get(material);
            return new WhitelistEmployee(row.employeeId(), status, row.secretVersion(), material);
        } catch (CharacterCodingException | IllegalArgumentException invalidData) {
            throw invalidInput();
        } finally {
            // WhitelistEmployee retains its defensive copy; these transient buffers are no longer needed.
            if (material != null) {
                Arrays.fill(material, (byte) 0);
            }
            if (encoded != null && encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    private static IllegalArgumentException invalidInput() {
        return new IllegalArgumentException("invalid whitelist employee mapping input");
    }
}
