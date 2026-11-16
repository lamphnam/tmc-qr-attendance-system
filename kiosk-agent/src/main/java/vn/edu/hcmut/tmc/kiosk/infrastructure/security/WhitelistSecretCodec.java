package vn.edu.hcmut.tmc.kiosk.infrastructure.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException.Reason;

/**
 * Local AES-256-GCM envelope, independent of Central's secret encoding. Format v1:
 * TMCW (4 bytes), version (1 byte), random nonce (12 bytes), ciphertext and GCM tag (16 bytes).
 * The header and caller-supplied row context are authenticated. No plaintext/legacy-format fallback exists.
 */
public final class WhitelistSecretCodec {

    private static final byte[] HEADER = {'T', 'M', 'C', 'W', 1};
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private WhitelistSecretCodec(SecretKey key) {
        this.key = key;
    }

    /** Missing configuration permits queue-only startup; every operation needing a key still fails closed. */
    public static WhitelistSecretCodec fromBase64Key(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            return new WhitelistSecretCodec(null);
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
            if (decoded.length != KEY_BYTES) {
                throw new WhitelistCacheException(Reason.UNAVAILABLE);
            }
            return new WhitelistSecretCodec(new SecretKeySpec(decoded, "AES"));
        } catch (IllegalArgumentException invalidConfiguration) {
            throw new WhitelistCacheException(Reason.UNAVAILABLE);
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    /** Returns a fresh envelope, without modifying input. Material and associatedData must be non-empty. */
    public byte[] encrypt(byte[] material, byte[] associatedData) {
        requireBytes(material);
        requireBytes(associatedData);
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(HEADER);
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(material);
            return ByteBuffer.allocate(HEADER.length + nonce.length + encrypted.length)
                    .put(HEADER).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException | ProviderException failure) {
            throw new WhitelistCacheException(Reason.UNAVAILABLE);
        }
    }

    /** Authenticates the entire envelope before returning plaintext; diagnostics never include input or causes. */
    public byte[] decrypt(byte[] envelope, byte[] associatedData) {
        requireBytes(associatedData);
        requireKey();
        if (envelope == null || envelope.length <= HEADER.length + NONCE_BYTES + TAG_BYTES
                || !Arrays.equals(HEADER, Arrays.copyOf(envelope, HEADER.length))) {
            throw new WhitelistCacheException(Reason.INVALID_CACHE);
        }
        try {
            byte[] nonce = Arrays.copyOfRange(envelope, HEADER.length, HEADER.length + NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            cipher.updateAAD(HEADER);
            cipher.updateAAD(associatedData);
            int offset = HEADER.length + NONCE_BYTES;
            return cipher.doFinal(envelope, offset, envelope.length - offset);
        } catch (AEADBadTagException unauthenticated) {
            throw new WhitelistCacheException(Reason.INVALID_CACHE);
        } catch (GeneralSecurityException | ProviderException failure) {
            throw new WhitelistCacheException(Reason.UNAVAILABLE);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new WhitelistCacheException(Reason.UNAVAILABLE);
        }
    }

    private static void requireBytes(byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("non-empty input is required");
        }
    }

    @Override
    public String toString() {
        return "WhitelistSecretCodec[key=<redacted>]";
    }
}
