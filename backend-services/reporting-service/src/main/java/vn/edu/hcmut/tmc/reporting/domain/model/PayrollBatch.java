package vn.edu.hcmut.tmc.reporting.domain.model;

import java.util.List;
import java.util.UUID;

/** The frozen content of one export batch, exactly what every attempt resends. */
public record PayrollBatch(UUID batchUuid, ExportPeriod period, List<PayrollRecord> records) {

    public PayrollBatch {
        if (batchUuid == null || period == null || records == null) {
            throw new IllegalArgumentException("batchUuid, period and records are required");
        }
        records = List.copyOf(records);
    }
}
