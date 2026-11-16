package vn.edu.hcmut.tmc.userqr.domain.exception;

public class FactoryNotFoundException extends RuntimeException {
    public FactoryNotFoundException() { super("Factory was not found"); }
}
