package vn.edu.hcmut.tmc.kiosk.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;

/** Composition only. A genuine revision-bearing source must be registered before this configuration is evaluated. */
@Configuration(proxyBeanMethods = false)
public class WhitelistSyncWiringConfig {

    @Bean
    @ConditionalOnBean(IWhitelistSnapshotSource.class)
    @ConditionalOnMissingBean(SyncWhitelistUseCase.class)
    SyncWhitelistUseCase syncWhitelistUseCase(IWhitelistSnapshotSource source, IWhitelistSnapshotStore store,
            CentralSyncSwitch centralSync, KioskIdentity identity, Clock clock) {
        return new SyncWhitelistUseCase(source, store, centralSync, identity, clock);
    }
}
