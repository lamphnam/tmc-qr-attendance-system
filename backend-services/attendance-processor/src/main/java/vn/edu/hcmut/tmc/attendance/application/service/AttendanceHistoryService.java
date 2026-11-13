package vn.edu.hcmut.tmc.attendance.application.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryPage;
import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceHistoryQuery;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceHistoryQuery;

/** Application boundary for validated history requests; endpoint and authorization remain unimplemented. */
@Service
public class AttendanceHistoryService {

    private final IAttendanceHistoryQuery history;

    public AttendanceHistoryService(IAttendanceHistoryQuery history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    public AttendanceHistoryPage query(String employeeId, String factoryId, Instant fromInclusive, Instant toExclusive,
                                       int pageNumber, int pageSize) {
        return history.find(new AttendanceHistoryQuery(employeeId, factoryId, fromInclusive, toExclusive, pageNumber, pageSize));
    }
}
