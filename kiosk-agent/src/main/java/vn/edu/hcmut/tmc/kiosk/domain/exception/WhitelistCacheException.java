package vn.edu.hcmut.tmc.kiosk.domain.exception;

import java.util.Objects;

/** Distinguishes cache failures from missing snapshots/employees without accepting sensitive diagnostic text. */
public final class WhitelistCacheException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE, INVALID_CACHE
    }

    private final Reason reason;

    public WhitelistCacheException(Reason reason) {
        super(switch (Objects.requireNonNull(reason, "reason")) {
            case UNAVAILABLE -> "whitelist cache unavailable";
            case INVALID_CACHE -> "whitelist cache is inconsistent";
        });
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
