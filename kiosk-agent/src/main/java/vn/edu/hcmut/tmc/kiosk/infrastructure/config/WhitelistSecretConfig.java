package vn.edu.hcmut.tmc.kiosk.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import vn.edu.hcmut.tmc.kiosk.infrastructure.security.WhitelistSecretCodec;

/** Keep key material out of configuration records whose generated toString could expose it. */
@Configuration
public class WhitelistSecretConfig {

    @Bean
    WhitelistSecretCodec whitelistSecretCodec(Environment environment) {
        return WhitelistSecretCodec.fromBase64Key(environment.getProperty("kiosk.whitelist.secret-key-base64"));
    }
}
