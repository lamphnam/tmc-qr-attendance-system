package vn.edu.hcmut.tmc.kiosk.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncScheduler;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;
import vn.edu.hcmut.tmc.kiosk.infrastructure.config.WhitelistHttpConfig;

/** Opt-in composition: the lifecycle scheduler waits one complete interval before its first pass. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kiosk.whitelist", name = "sync-enabled", havingValue = "true")
@Import(WhitelistHttpConfig.class)
public class WhitelistSyncWiringConfig {

    @Bean
    @ConditionalOnMissingBean(WhitelistSyncScheduler.class)
    WhitelistSyncScheduler whitelistSyncScheduler(SyncWhitelistUseCase useCase,
            @Value("${kiosk.whitelist.sync-interval-ms:60000}") long intervalMs) {
        return new WhitelistSyncScheduler(useCase, intervalMs);
    }

    @Bean
    @ConditionalOnMissingBean(SyncWhitelistUseCase.class)
    SyncWhitelistUseCase syncWhitelistUseCase(IWhitelistSnapshotSource source, IWhitelistSnapshotStore store,
            CentralSyncSwitch centralSync, KioskIdentity identity, Clock clock) {
        return new SyncWhitelistUseCase(source, store, centralSync, identity, clock);
    }
}
