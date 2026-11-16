package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.RestWhitelistSnapshotSource;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistEmployeeMapper;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpTransport;

/** Avoid storing a credential in configuration records with generated toString methods. No request is scheduled. */
@Configuration
public class WhitelistHttpConfig {

    @Bean
    @ConditionalOnProperty(prefix = "kiosk.whitelist", name = "sync-enabled", havingValue = "true")
    @ConditionalOnMissingBean(IWhitelistSnapshotSource.class)
    RestWhitelistSnapshotSource whitelistSnapshotSource(WhitelistHttpTransport transport, WhitelistEmployeeMapper mapper,
            KioskIdentity identity, Clock clock) {
        return new RestWhitelistSnapshotSource(transport, mapper, identity, clock);
    }

    @Bean
    WhitelistEmployeeMapper whitelistEmployeeMapper() {
        return new WhitelistEmployeeMapper();
    }

    @Bean
    WhitelistHttpTransport whitelistHttpTransport(@Qualifier("centralRestClient") RestClient client,
            KioskIdentity identity, Environment environment, ObjectMapper mapper) {
        return new WhitelistHttpTransport(client, identity,
                environment.getProperty("kiosk.whitelist.api-key"), mapper);
    }
}
