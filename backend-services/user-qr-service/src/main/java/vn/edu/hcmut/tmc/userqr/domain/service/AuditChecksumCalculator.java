package vn.edu.hcmut.tmc.userqr.domain.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import vn.edu.hcmut.tmc.userqr.domain.model.AuditEvent;

/** SHA-256 over an unambiguous length-prefixed UTF-8 encoding and the preceding checksum. */
public class AuditChecksumCalculator {
    public static final String GENESIS_HASH = "0".repeat(64);

    public String calculate(AuditEvent event, String previousHash) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String value : new String[]{"TMC-AUDIT-V1", event.logId(), event.timestamp().toString(),
                    event.actorId(), event.actorRole(), event.actionType(), event.targetResource(),
                    event.payloadSummary(), event.status(), previousHash}) {
                if (value == null) {
                    out.writeInt(-1);
                } else {
                    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK SHA-256 checksum calculation failed", e);
        }
    }
}
