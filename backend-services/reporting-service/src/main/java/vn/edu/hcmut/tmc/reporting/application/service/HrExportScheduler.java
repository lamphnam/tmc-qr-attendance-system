package vn.edu.hcmut.tmc.reporting.application.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background trigger only: it decides nothing. The 01:00 rule lives in {@code ExportSchedule}, so polling every few
 * seconds both runs on time and catches up a run missed while the service was down.
 */
@Component
@ConditionalOnProperty(name = "tmc.hr-export.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class HrExportScheduler {

    private static final Logger log = LoggerFactory.getLogger(HrExportScheduler.class);

    private final HrExportService exportService;

    public HrExportScheduler(HrExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(initialDelayString = "${tmc.hr-export.poll-interval-ms}", fixedDelayString = "${tmc.hr-export.poll-interval-ms}")
    void tick() {
        try {
            List<HrExportResult> results = exportService.runDueWork();
            if (!results.isEmpty()) {
                log.info("hr-export poll: {} attempt(s)", results.size());
            }
        } catch (RuntimeException e) {
            // The poll must survive a database outage, or a missed month would never be caught up.
            log.warn("hr-export poll failed, will retry on the next tick: {}", e.getClass().getSimpleName());
        }
    }
}
