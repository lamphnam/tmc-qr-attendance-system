package vn.edu.hcmut.tmc.attendance.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.edu.hcmut.tmc.attendance.domain.service.IdempotencyChecker;

/** Wires framework-free domain services; the domain package itself carries no Spring annotations. */
@Configuration
public class DomainBeansConfig {

    @Bean
    IdempotencyChecker idempotencyChecker() {
        return new IdempotencyChecker();
    }
}
