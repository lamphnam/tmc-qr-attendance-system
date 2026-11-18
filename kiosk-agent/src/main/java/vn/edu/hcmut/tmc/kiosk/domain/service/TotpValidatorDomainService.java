package vn.edu.hcmut.tmc.kiosk.domain.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.OptionalLong;

import vn.edu.hcmut.tmc.kiosk.domain.port.IHmacSha256;

/** ADR-02 verification window. The matched server-side counter is the local replay key. */
public class TotpValidatorDomainService {
    private static final long STEP_SECONDS = 30;

    private final Clock clock;
    private final IHmacSha256 hmac;

    public TotpValidatorDomainService(Clock clock, IHmacSha256 hmac) {
        this.clock = clock;
        this.hmac = hmac;
    }

    public OptionalLong match(byte[] secret, String code) {
        long seconds = clock.instant().getEpochSecond();
        if (seconds < 0 || secret == null || secret.length == 0 || code == null || !code.matches("[0-9]{6}")) {
            return OptionalLong.empty();
        }
        long step = seconds / STEP_SECONDS;
        byte[] candidate = code.getBytes(StandardCharsets.US_ASCII);
        boolean current = MessageDigest.isEqual(candidate, codeAt(secret, step));
        boolean previous = step > 0 && MessageDigest.isEqual(candidate, codeAt(secret, step - 1));
        if (previous) {
            return OptionalLong.of(step - 1);
        }
        return current ? OptionalLong.of(step) : OptionalLong.empty();
    }

    private byte[] codeAt(byte[] secret, long step) {
        byte[] hash = hmac.digest(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ByteBuffer.wrap(hash).getInt(offset) & Integer.MAX_VALUE;
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000).getBytes(StandardCharsets.US_ASCII);
    }
}
