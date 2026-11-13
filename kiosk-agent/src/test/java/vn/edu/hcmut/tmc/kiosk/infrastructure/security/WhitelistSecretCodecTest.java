package vn.edu.hcmut.tmc.kiosk.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException.Reason;

class WhitelistSecretCodecTest {

    private static final byte[] MATERIAL = {1, 3, 5, 7, 9};
    private static final byte[] CONTEXT = "synthetic-row-context".getBytes(StandardCharsets.UTF_8);

    private static String temporaryKey(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        try {
            return Base64.getEncoder().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void assertSafeFailure(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(WhitelistCacheException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo(reason == Reason.INVALID_CACHE
                    ? "whitelist cache is inconsistent" : "whitelist cache unavailable");
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }

    @Test
    void roundTripAcrossCodecInstancesLeavesInputsUnchangedAndRedactsDiagnostics() {
        String key = temporaryKey(32);
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(key);
        byte[] material = MATERIAL.clone();
        byte[] context = CONTEXT.clone();
        byte[] envelope = codec.encrypt(material, context);
        byte[] before = envelope.clone();

        assertThat(WhitelistSecretCodec.fromBase64Key(key).decrypt(envelope, context)).containsExactly(MATERIAL);
        assertThat(material).containsExactly(MATERIAL);
        assertThat(context).containsExactly(CONTEXT);
        assertThat(envelope).containsExactly(before);
        assertThat(Arrays.equals(envelope, material)).isFalse();
        assertThat(codec.toString()).isEqualTo("WhitelistSecretCodec[key=<redacted>]");
    }

    @Test
    void everyEncryptionUsesANewNonceAndARecognizableVersionedEnvelope() {
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        Set<String> nonces = new HashSet<>();
        for (int i = 0; i < 32; i++) {
            byte[] envelope = codec.encrypt(MATERIAL, CONTEXT);
            assertThat(Arrays.copyOf(envelope, 5)).containsExactly((byte) 'T', (byte) 'M', (byte) 'C', (byte) 'W', (byte) 1);
            assertThat(envelope.length).isEqualTo(5 + 12 + MATERIAL.length + 16);
            assertThat(nonces.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(envelope, 5, 17)))).isTrue();
            assertThat(codec.decrypt(envelope, CONTEXT)).containsExactly(MATERIAL);
        }
    }

    @Test
    void wrongKeyAndWrongAssociatedContextFailAuthentication() {
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        byte[] envelope = codec.encrypt(MATERIAL, CONTEXT);
        WhitelistSecretCodec wrongKey = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        assertSafeFailure(() -> wrongKey.decrypt(envelope, CONTEXT), Reason.INVALID_CACHE);
        assertSafeFailure(() -> codec.decrypt(envelope, new byte[] {42}), Reason.INVALID_CACHE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 5, 17, 37})
    void alteredHeaderVersionNonceCiphertextOrTagIsRejected(int index) {
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        byte[] envelope = codec.encrypt(MATERIAL, CONTEXT);
        envelope[index] ^= 1;
        assertSafeFailure(() -> codec.decrypt(envelope, CONTEXT), Reason.INVALID_CACHE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 17, 32, 33, 34, 37})
    void truncatedEnvelopeNeverReturnsPartialPlaintext(int length) {
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        byte[] envelope = Arrays.copyOf(codec.encrypt(MATERIAL, CONTEXT), length);
        assertSafeFailure(() -> codec.decrypt(envelope, CONTEXT), Reason.INVALID_CACHE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingKeyFailsOnBothEncryptionAndDecryption(String key) {
        byte[] validEnvelope = WhitelistSecretCodec.fromBase64Key(temporaryKey(32)).encrypt(MATERIAL, CONTEXT);
        WhitelistSecretCodec missing = WhitelistSecretCodec.fromBase64Key(key);
        assertSafeFailure(() -> missing.encrypt(MATERIAL, CONTEXT), Reason.UNAVAILABLE);
        assertSafeFailure(() -> missing.decrypt(validEnvelope, CONTEXT), Reason.UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 24, 31, 33, 64})
    void rejectsKeysOtherThan256BitsWithoutLeakingConfiguration(int length) {
        assertSafeFailure(() -> WhitelistSecretCodec.fromBase64Key(temporaryKey(length)), Reason.UNAVAILABLE);
    }

    @Test
    void rejectsMalformedKeyAndNullCiphertextSafely() {
        assertSafeFailure(() -> WhitelistSecretCodec.fromBase64Key("not-a-base64-key!"), Reason.UNAVAILABLE);
        WhitelistSecretCodec codec = WhitelistSecretCodec.fromBase64Key(temporaryKey(32));
        assertSafeFailure(() -> codec.decrypt(null, CONTEXT), Reason.INVALID_CACHE);
        assertThatThrownBy(() -> codec.encrypt(new byte[0], CONTEXT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encrypt(MATERIAL, new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
