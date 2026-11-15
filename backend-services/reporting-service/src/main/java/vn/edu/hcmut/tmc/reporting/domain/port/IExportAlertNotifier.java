package vn.edu.hcmut.tmc.reporting.domain.port;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportAlert;

/**
 * Tells HR/Admin that a batch needs manual action (NFR-6). M1 names no delivery channel (mail, SMS, chat), so the use
 * case only states that an alert is due. Implementations must not throw: a failed alert must never undo the batch state.
 */
public interface IExportAlertNotifier {

    void exportFailed(ExportAlert alert);
}
