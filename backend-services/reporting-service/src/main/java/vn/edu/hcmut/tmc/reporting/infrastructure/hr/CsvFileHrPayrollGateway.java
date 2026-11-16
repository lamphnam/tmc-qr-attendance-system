package vn.edu.hcmut.tmc.reporting.infrastructure.hr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;

/**
 * Channel {@code file}: writes the batch as a CSV file into a drop directory that a payroll system imports from (M1 says
 * "batch export to HR/Payroll" without fixing a protocol, and the HR API contract is still DRAFT, D09).
 *
 * <p>One file per batch, {@code hr-export_<period>_<batchUuid>.csv}. It is written under a temporary name and then
 * renamed, so an importer never sees half a file. An existing file is never overwritten: a resend reports a duplicate.
 * Nothing confirms that payroll imported the file, so success is {@code DELIVERED}, never {@code ACKNOWLEDGED}.
 */
@Component
@ConditionalOnProperty(name = "tmc.hr-export.channel", havingValue = "file")
public class CsvFileHrPayrollGateway implements IHrPayrollGateway {

    static final String HEADER = "batch_uuid,period,attendance_id,employee_id,factory_id,gate_id,direction,occurred_at";

    private final Path directory;

    public CsvFileHrPayrollGateway(@Value("${tmc.hr-export.file.directory}") Path directory) {
        this.directory = directory;
    }

    @Override
    public DeliveryResult send(PayrollBatch batch) {
        Path target = directory.resolve(fileName(batch));
        if (Files.exists(target)) {
            return DeliveryResult.delivered(true);
        }
        Path temporary = directory.resolve("." + target.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            writeCsv(batch, temporary);
        } catch (IOException e) {
            // Includes FileAlreadyExistsException when something that is not a directory sits at the drop path.
            deleteQuietly(temporary);
            return DeliveryResult.retryable("I/O error (" + e.getClass().getSimpleName() + ")");
        }
        try {
            Files.move(temporary, target);
            return DeliveryResult.delivered(false);
        } catch (FileAlreadyExistsException concurrentWriter) {
            // Only here does "already exists" mean the batch file itself: another writer finished first.
            deleteQuietly(temporary);
            return DeliveryResult.delivered(true);
        } catch (IOException e) {
            deleteQuietly(temporary);
            return DeliveryResult.retryable("I/O error (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static void writeCsv(PayrollBatch batch, Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.write("\r\n");
            for (PayrollRecord r : batch.records()) {
                out.write(String.join(",",
                        batch.batchUuid().toString(), batch.period().label(), r.attendanceId().toString(),
                        csv(r.employeeId()), csv(r.factoryId()), csv(r.gateId()), csv(r.direction()),
                        r.occurredAt().toString()));
                out.write("\r\n");
            }
        }
    }

    static String fileName(PayrollBatch batch) {
        return "hr-export_" + batch.period().label() + "_" + batch.batchUuid() + ".csv";
    }

    /** RFC 4180 quoting for free-text fields; ids and timestamps never need it. */
    static String csv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover dot-file is never imported; the next attempt overwrites it.
        }
    }
}
