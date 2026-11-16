package vn.edu.hcmut.tmc.reporting.infrastructure.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.edu.hcmut.tmc.reporting.domain.model.ExportPeriod;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;

/** Channel {@code file} against a real directory: the shared contract plus file-specific checks. */
class CsvFileHrPayrollGatewayTest extends HrPayrollGatewayContract {

    @TempDir
    Path temp;

    private Path dropDirectory() {
        return temp.resolve("hr-drop");
    }

    @Override
    protected IHrPayrollGateway channel() {
        return new CsvFileHrPayrollGateway(dropDirectory());
    }

    /** Something that is not a directory occupies the drop path, so nothing can be written there. */
    @Override
    protected void breakReceiver() throws IOException {
        Files.writeString(dropDirectory(), "not a directory");
    }

    @Override
    protected void repairReceiver() throws IOException {
        Files.delete(dropDirectory());
    }

    @Override
    protected List<UUID> heldByReceiver() throws IOException {
        List<UUID> ids = new ArrayList<>();
        if (!Files.isDirectory(dropDirectory())) {
            return ids;
        }
        try (Stream<Path> files = Files.list(dropDirectory())) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".csv")).toList()) {
                Files.readAllLines(file, StandardCharsets.UTF_8).stream().skip(1)
                        .forEach(line -> ids.add(UUID.fromString(line.split(",")[2])));
            }
        }
        return ids;
    }

    @Override
    protected boolean receiverConfirms() {
        return false;
    }

    @Test
    void theFileHasAHeaderOneLinePerRecordAndANameThatCarriesPeriodAndBatch() throws IOException {
        PayrollBatch batch = batch("2025-06", 2);

        channel().send(batch);

        Path file = dropDirectory().resolve("hr-export_2025-06_" + batch.batchUuid() + ".csv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo(CsvFileHrPayrollGateway.HEADER);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith(batch.batchUuid() + ",2025-06," + batch.records().get(0).attendanceId());
    }

    @Test
    void noTemporaryFileIsLeftBehindAfterSuccessOrFailure() throws IOException {
        channel().send(batch("2025-07", 1));
        try (Stream<Path> files = Files.list(dropDirectory())) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void anExistingBatchFileIsNeverOverwritten() throws IOException {
        PayrollBatch batch = batch("2025-08", 1);
        channel().send(batch);
        Path file = dropDirectory().resolve(CsvFileHrPayrollGateway.fileName(batch));
        Files.writeString(file, "imported-and-annotated-by-payroll");

        channel().send(batch);

        assertThat(Files.readString(file)).isEqualTo("imported-and-annotated-by-payroll");
    }

    @Test
    void freeTextWithACommaIsQuoted() throws IOException {
        ExportPeriod p = ExportPeriod.parse("2025-09");
        PayrollBatch batch = new PayrollBatch(p.batchUuid(), p, List.of(new PayrollRecord(UUID.randomUUID(),
                "EMP,1", "BD", "BD-G01", "IN", p.startInclusive())));

        channel().send(batch);

        String content = Files.readString(dropDirectory().resolve(CsvFileHrPayrollGateway.fileName(batch)));
        assertThat(content).contains(",\"EMP,1\",BD,");
    }
}
