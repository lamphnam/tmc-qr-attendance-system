package vn.edu.hcmut.tmc.userqr.domain.model;

public record AuditLog(long sequence, AuditEvent event, String previousHash, String checksum) { }
