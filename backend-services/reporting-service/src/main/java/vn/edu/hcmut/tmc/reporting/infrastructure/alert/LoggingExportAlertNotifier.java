package vn.edu.hcmut.tmc.reporting.infrastructure.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportAlert;
import vn.edu.hcmut.tmc.reporting.domain.port.IExportAlertNotifier;

/**
 * The only alert channel that exists: one ERROR line with a fixed {@code HR_EXPORT_ALERT} marker that log monitoring
 * can match, plus the FAILED batch shown on the dashboard. Mail/SMS delivery to HR and Admin is NOT implemented.
 */
@Component
public class LoggingExportAlertNotifier implements IExportAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingExportAlertNotifier.class);

    @Override
    public void exportFailed(ExportAlert alert) {
        log.error("HR_EXPORT_ALERT period={} batch={} attempts={} reason=\"{}\" action=\"export manually before the 12:00 reconciliation\"",
                alert.period(), alert.batchUuid(), alert.attempts(), alert.reason());
    }
}
