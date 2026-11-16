package vn.edu.hcmut.tmc.reporting.domain.port;

import java.util.Optional;

import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;

/** Read-only view of export state for dashboards; throws {@code ReportingStorageException} when the DB is down. */
public interface IHrExportStatusReader {

    /** The batch of the most recent period, if any batch exists. */
    Optional<HrExportBatch> latest();
}
