package vn.edu.hcmut.tmc.attendance.application.service;

import java.util.List;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.attendance.domain.model.AttendanceRecord;
import vn.edu.hcmut.tmc.attendance.domain.repository.IAttendanceEventsQuery;

/** Validates the bounded dev inspection request without changing ingestion or History policies. */
@Service
public class AttendanceEventsService {

    private final IAttendanceEventsQuery events;

    public AttendanceEventsService(IAttendanceEventsQuery events) {
        this.events = events;
    }

    public List<AttendanceRecord> recent(int limit, String employeeId) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("invalid attendance event query");
        }
        if (employeeId != null) {
            employeeId = employeeId.strip();
            if (employeeId.isEmpty() || employeeId.length() > 32
                    || employeeId.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid attendance event query");
            }
        }
        return events.findRecent(limit, employeeId);
    }
}
