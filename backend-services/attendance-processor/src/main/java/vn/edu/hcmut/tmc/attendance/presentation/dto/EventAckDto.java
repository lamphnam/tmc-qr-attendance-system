package vn.edu.hcmut.tmc.attendance.presentation.dto;

/** Ack for a single event. Only ever produced after the database commit. */
public record EventAckDto(String attendanceId, String status, boolean duplicate) {
}
