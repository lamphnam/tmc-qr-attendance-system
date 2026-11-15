package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * A factory attendance report request (FR-4.1). Days are factory days ({@link FactoryCalendar}); {@code to} is
 * inclusive. The range is capped at {@value #MAX_DAYS} days so one request stays a bounded, index-backed query (NFR-7).
 */
public record AttendanceReportQuery(String factoryId, LocalDate from, LocalDate to, String department,
                                    int offset, int limit, DataScope scope) {

    public static final int MAX_DAYS = 31;
    public static final int MAX_LIMIT = 500;
    private static final Pattern FACTORY_ID = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    public AttendanceReportQuery {
        if (factoryId == null || !FACTORY_ID.matcher(factoryId).matches()) {
            throw new IllegalArgumentException("factoryId must be 1-16 letters, digits, '-' or '_'");
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("a report covers at most " + MAX_DAYS + " days");
        }
        if (department != null && (department.isBlank() || department.length() > 100)) {
            throw new IllegalArgumentException("department must be 1-100 characters when given");
        }
        if (offset < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("offset must be >= 0 and limit between 1 and " + MAX_LIMIT);
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
    }

    static boolean isValidFactoryId(String factoryId) {
        return factoryId != null && FACTORY_ID.matcher(factoryId).matches();
    }
}
