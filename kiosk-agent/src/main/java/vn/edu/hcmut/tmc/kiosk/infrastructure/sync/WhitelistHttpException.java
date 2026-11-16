package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.util.Objects;
import java.util.OptionalInt;

/** Fixed diagnostics only: never attach HTTP/parser causes, response bodies, headers or credentials. */
public final class WhitelistHttpException extends RuntimeException {

    public enum Reason {
        MISSING_CREDENTIAL, INVALID_CONFIGURATION, HTTP_ERROR, UNAVAILABLE, INVALID_RESPONSE
    }

    private final Reason reason;
    private final int httpStatus;

    public WhitelistHttpException(Reason reason) {
        this(reason, 0);
    }

    private WhitelistHttpException(Reason reason, int httpStatus) {
        super(switch (Objects.requireNonNull(reason, "reason")) {
            case MISSING_CREDENTIAL -> "whitelist HTTP credential is not configured";
            case INVALID_CONFIGURATION -> "whitelist HTTP configuration is invalid";
            case HTTP_ERROR -> "whitelist HTTP request was unsuccessful";
            case UNAVAILABLE -> "whitelist HTTP service is unavailable";
            case INVALID_RESPONSE -> "whitelist HTTP response is invalid";
        });
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public static WhitelistHttpException httpError(int status) {
        return new WhitelistHttpException(Reason.HTTP_ERROR, status);
    }

    public Reason reason() {
        return reason;
    }

    public OptionalInt httpStatus() {
        return httpStatus == 0 ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }
}
