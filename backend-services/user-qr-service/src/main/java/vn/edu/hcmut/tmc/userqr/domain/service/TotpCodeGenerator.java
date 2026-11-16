package vn.edu.hcmut.tmc.userqr.domain.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238, HMAC-SHA256, six digits, T0=0 and a 30-second time step (ADR-02). */
public class TotpCodeGenerator {
    public static final long TIME_STEP_SECONDS = 30;

    public String generateTotp(String sharedSecret, Instant timestamp) {
        if (timestamp == null || timestamp.getEpochSecond() < 0) {
            throw new IllegalArgumentException("Timestamp must be at or after the Unix epoch");
        }
        return generateAtStep(sharedSecret, timestamp.getEpochSecond() / TIME_STEP_SECONDS);
    }

    public boolean verifyTotpWithWindow(String sharedSecret, String candidateCode, Instant scanTime) {
        if (candidateCode == null || !candidateCode.matches("[0-9]{6}")) return false;
        if (scanTime == null || scanTime.getEpochSecond() < 0) return false;
        long step = scanTime.getEpochSecond() / TIME_STEP_SECONDS;
        byte[] candidate = candidateCode.getBytes(StandardCharsets.US_ASCII);
        boolean current = MessageDigest.isEqual(candidate,
                generateAtStep(sharedSecret, step).getBytes(StandardCharsets.US_ASCII));
        boolean previous = step > 0 && MessageDigest.isEqual(candidate,
                generateAtStep(sharedSecret, step - 1).getBytes(StandardCharsets.US_ASCII));
        return current | previous;
    }

    private String generateAtStep(String sharedSecret, long step) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalArgumentException("Shared secret is required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK HMAC-SHA256 is unavailable", e);
        }
    }
}
