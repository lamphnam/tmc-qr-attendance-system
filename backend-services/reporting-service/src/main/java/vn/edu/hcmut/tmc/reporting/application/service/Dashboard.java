package vn.edu.hcmut.tmc.reporting.application.service;

import vn.edu.hcmut.tmc.reporting.domain.model.DashboardSnapshot;
import vn.edu.hcmut.tmc.reporting.domain.model.HrExportBatch;

/** Factory day counts plus the latest HR export batch, where a FAILED export is visible to HR/Admin. */
public record Dashboard(DashboardSnapshot attendance, HrExportBatch latestExport) {
}
