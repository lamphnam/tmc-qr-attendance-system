package vn.edu.hcmut.tmc.userqr.domain.exception;

public class EmployeeNotFoundException extends RuntimeException {
    public EmployeeNotFoundException() { super("Employee was not found"); }
}
