package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One employee on one factory day. {@code fullName}/{@code department} are null when the employee id is unknown to the
 * central {@code employees} table (events are immutable edge facts with no FK, B13). {@code firstIn}/{@code lastOut} are
 * null when the day has no scan in that direction.
 */
public record DailyAttendanceRow(LocalDate workDate, String employeeId, String fullName, String department,
                                 Instant firstIn, Instant lastOut, int inCount, int outCount) {
}
