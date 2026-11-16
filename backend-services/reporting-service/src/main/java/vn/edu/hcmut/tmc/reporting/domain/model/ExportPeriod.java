package vn.edu.hcmut.tmc.reporting.domain.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * One payroll month in {@link FactoryCalendar#ZONE} (D10 / B24).
 *
 * <p>The batch id is a pure function of the month, so every path that exports October 2026 (the scheduled run, a retry
 * after a restart, a manual trigger) computes the same {@code batchUuid} and therefore lands on the same batch.
 */
public record ExportPeriod(YearMonth yearMonth) {

    private static final String BATCH_NAMESPACE = "tmc-hr-export:";

    public ExportPeriod {
        if (yearMonth == null) {
            throw new IllegalArgumentException("period must not be null");
        }
        if (yearMonth.getYear() < 2000 || yearMonth.getYear() > 9999) {
            throw new IllegalArgumentException("period year must be between 2000 and 9999");
        }
    }

    /** @throws IllegalArgumentException unless {@code text} is {@code YYYY-MM} */
    public static ExportPeriod parse(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("period must look like YYYY-MM");
        }
        try {
            return new ExportPeriod(YearMonth.parse(text));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("period must look like YYYY-MM");
        }
    }

    public static ExportPeriod monthContaining(Instant instant) {
        return new ExportPeriod(YearMonth.from(instant.atZone(FactoryCalendar.ZONE)));
    }

    public ExportPeriod previous() {
        return new ExportPeriod(yearMonth.minusMonths(1));
    }

    public Instant startInclusive() {
        return FactoryCalendar.startOf(yearMonth.atDay(1));
    }

    public Instant endExclusive() {
        return FactoryCalendar.startOf(yearMonth.plusMonths(1).atDay(1));
    }

    /** A month can only be frozen into a batch once it is over; otherwise later scans would be missing from it. */
    public boolean isCompleteAt(Instant now) {
        return !now.isBefore(endExclusive());
    }

    public UUID batchUuid() {
        return UUID.nameUUIDFromBytes((BATCH_NAMESPACE + label()).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code YYYY-MM}, the value stored in {@code hr_export_batches.period}. */
    public String label() {
        return yearMonth.toString();
    }

    @Override
    public String toString() {
        return label();
    }
}
