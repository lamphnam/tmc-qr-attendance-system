package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;

class WhitelistSecretConfigTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WhitelistSecretConfig.class);

    @Test
    void loadsExternallyConfiguredKeyIntoInfrastructureBean() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=" + encoded).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WhitelistSecretCodec.class);
            WhitelistSecretCodec codec = application.getBean(WhitelistSecretCodec.class);
            byte[] material = {2, 4, 6};
            byte[] aad = {1};
            assertThat(codec.decrypt(codec.encrypt(material, aad), aad)).containsExactly(material);
        });
    }

    @Test
    void missingKeyAllowsQueueOnlyStartupButNeverSecretOperations() {
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=").run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(WhitelistSecretCodec.class);
            assertThatThrownBy(() -> application.getBean(WhitelistSecretCodec.class).encrypt(new byte[] {1}, new byte[] {2}))
                    .isInstanceOf(WhitelistCacheException.class).hasMessage("whitelist cache unavailable").hasNoCause();
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 24, 31, 33})
    void wrongKeyLengthsFailConfigurationInsteadOfReplacingTheKey(int length) {
        byte[] key = new byte[length];
        new SecureRandom().nextBytes(key);
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=" + Base64.getEncoder().encodeToString(key))
                .run(application -> assertThat(application).hasFailed()
                        .getFailure().hasRootCauseMessage("whitelist cache unavailable"));
        Arrays.fill(key, (byte) 0);
    }

    @Test
    void sameExternalKeySurvivesContextRestartWithoutReencrypting() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encoded = Base64.getEncoder().encodeToString(key);
        Arrays.fill(key, (byte) 0);
        var ciphertext = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        byte[] material = {2, 4, 6};
        byte[] aad = {1};
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=" + encoded).run(application ->
                ciphertext.set(application.getBean(WhitelistSecretCodec.class).encrypt(material, aad)));
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=" + encoded).run(application ->
                assertThat(application.getBean(WhitelistSecretCodec.class).decrypt(ciphertext.get(), aad))
                        .containsExactly(material));
    }

    @Test
    void invalidConfiguredKeyFailsBeanCreationWithSafeRootCause() {
        context.withPropertyValues("kiosk.whitelist.secret-key-base64=invalid!").run(application -> {
            assertThat(application).hasFailed();
            assertThat(application.getStartupFailure()).hasRootCauseInstanceOf(WhitelistCacheException.class)
                    .hasRootCauseMessage("whitelist cache unavailable");
        });
    }
}
