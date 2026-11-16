package vn.edu.hcmut.tmc.reporting.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import vn.edu.hcmut.tmc.reporting.application.service.HrExportSettings;
import vn.edu.hcmut.tmc.reporting.domain.model.DataScope;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportRetryPolicy;
import vn.edu.hcmut.tmc.reporting.domain.service.ExportSchedule;

/**
 * Composition root, outside the four layers on purpose (as in {@code kiosk-agent}): the one place that reads properties
 * and builds the framework-free domain objects. The payroll channel is NOT chosen here: each {@code IHrPayrollGateway}
 * adapter declares the {@code tmc.hr-export.channel} value it serves, so adding a channel adds one class and edits none.
 */
@Configuration
@EnableScheduling
public class ReportingWiringConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DataScope dataScope(@Value("${tmc.reporting.include-dev-fixtures:false}") boolean includeDevFixtures) {
        return new DataScope(includeDevFixtures);
    }

    @Bean
    ExportSchedule exportSchedule(@Value("${tmc.hr-export.run-at:01:00}") String runAt) {
        return new ExportSchedule(LocalTime.parse(runAt));
    }

    @Bean
    ExportRetryPolicy exportRetryPolicy(@Value("${tmc.hr-export.backoff-base-ms}") long baseMs,
                                        @Value("${tmc.hr-export.backoff-max-ms}") long maxMs,
                                        @Value("${tmc.hr-export.retry-window-ms}") long windowMs) {
        return new ExportRetryPolicy(Duration.ofMillis(baseMs), Duration.ofMillis(maxMs), Duration.ofMillis(windowMs));
    }

    @Bean
    HrExportSettings hrExportSettings(@Value("${tmc.hr-export.attempt-lease-ms}") long leaseMs,
                                      @Value("${tmc.hr-export.due-batch-limit:10}") int dueBatchLimit) {
        return new HrExportSettings(Duration.ofMillis(leaseMs), dueBatchLimit);
    }
}
