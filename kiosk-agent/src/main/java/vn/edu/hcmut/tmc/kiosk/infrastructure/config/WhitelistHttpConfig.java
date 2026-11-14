package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpTransport;

/** Avoid storing a credential in configuration records with generated toString methods. No request is scheduled. */
@Configuration
public class WhitelistHttpConfig {

    @Bean
    WhitelistHttpTransport whitelistHttpTransport(@Qualifier("centralRestClient") RestClient client,
            KioskIdentity identity, Environment environment, ObjectMapper mapper) {
        return new WhitelistHttpTransport(client, identity,
                environment.getProperty("kiosk.whitelist.api-key"), mapper);
    }
}
