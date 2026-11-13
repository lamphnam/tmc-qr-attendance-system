package vn.edu.hcmut.tmc.userqr.infrastructure.config;

import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.hcmut.tmc.userqr.domain.service.AuditChecksumCalculator;
import vn.edu.hcmut.tmc.userqr.domain.service.TotpCodeGenerator;
import vn.edu.hcmut.tmc.userqr.infrastructure.persistence.PostgresAuditLogRepository;
import vn.edu.hcmut.tmc.userqr.infrastructure.persistence.PostgresEmployeeQrRepository;

@Configuration
public class DomainBeansConfig {
    @Bean
    TotpCodeGenerator totpCodeGenerator() { return new TotpCodeGenerator(); }

    @Bean
    AuditChecksumCalculator auditChecksumCalculator() { return new AuditChecksumCalculator(); }

    @Bean
    Clock qrClock() { return Clock.systemUTC(); }

    @Bean
    SecureRandom qrSecureRandom() { return new SecureRandom(); }

    @Bean
    PostgresEmployeeQrRepository employeeQrRepository(JdbcTemplate jdbc) {
        return new PostgresEmployeeQrRepository(jdbc);
    }

    @Bean
    PostgresAuditLogRepository auditLogRepository(JdbcTemplate jdbc, AuditChecksumCalculator checksums) {
        return new PostgresAuditLogRepository(jdbc, checksums);
    }
}
