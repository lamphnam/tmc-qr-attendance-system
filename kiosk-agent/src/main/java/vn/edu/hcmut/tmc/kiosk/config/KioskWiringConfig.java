package vn.edu.hcmut.tmc.kiosk.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncSettings;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.IHmacSha256;
import vn.edu.hcmut.tmc.kiosk.domain.service.RetryBackoffPolicy;
import vn.edu.hcmut.tmc.kiosk.domain.service.TotpValidatorDomainService;

/**
 * Composition root. Lives outside the four layers on purpose: it is the one place allowed to know about
 * properties, application beans and framework-free domain objects at the same time.
 */
@Configuration
@EnableConfigurationProperties(KioskProperties.class)
@EnableScheduling
public class KioskWiringConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TotpValidatorDomainService totpValidator(Clock clock, IHmacSha256 hmac) {
        return new TotpValidatorDomainService(clock, hmac);
    }

    @Bean
    KioskIdentity kioskIdentity(KioskProperties properties) {
        return new KioskIdentity(properties.id(), properties.factoryId(), properties.defaultGateId());
    }

    @Bean
    RetryBackoffPolicy retryBackoffPolicy(KioskProperties properties) {
        return new RetryBackoffPolicy(Duration.ofMillis(properties.sync().backoffBaseMs()),
                Duration.ofMillis(properties.sync().backoffMaxMs()));
    }

    @Bean
    CentralSyncSwitch centralSyncSwitch(KioskProperties properties) {
        return new CentralSyncSwitch(properties.central().disabled());
    }

    @Bean
    SyncSettings syncSettings(KioskProperties properties) {
        return new SyncSettings(properties.sync().batchSize());
    }
}
