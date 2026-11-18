package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncWhitelistUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.WhitelistSyncReport;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.ErrorResponseDto;

/** Dev-only manual trigger; all fetching, validation, concurrency and durable replacement stay in the use case. */
@RestController
@Profile("dev")
public class DevWhitelistSyncController {

    private final boolean enabled;
    private final Optional<SyncWhitelistUseCase> sync;

    public DevWhitelistSyncController(@Value("${kiosk.whitelist.sync-enabled:false}") boolean enabled,
            Optional<SyncWhitelistUseCase> sync) {
        this.enabled = enabled;
        this.sync = sync;
    }

    @PostMapping("/api/dev/kiosk/whitelist/sync")
    public ResponseEntity<?> run(@RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (!enabled) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WHITELIST_SYNC_DISABLED", "whitelist sync is disabled", requestId);
        }
        if (sync.isEmpty()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "WHITELIST_SYNC_UNAVAILABLE", "whitelist sync is unavailable", requestId);
        }
        try {
            WhitelistSyncReport report = sync.orElseThrow().run();
            return switch (report.status()) {
                case UPDATED, NOT_NEWER -> response(HttpStatus.OK)
                        .body(new Result(report.status().name(), report.snapshotVersion().orElseThrow()));
                case OFFLINE -> error(HttpStatus.CONFLICT, "CENTRAL_SYNC_DISABLED", "central sync is offline", requestId);
                case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "WHITELIST_SYNC_IN_PROGRESS", "whitelist sync is already running", requestId);
                case SOURCE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WHITELIST_SOURCE_UNAVAILABLE", "whitelist source is unavailable", requestId);
                case INVALID_SNAPSHOT -> error(HttpStatus.BAD_GATEWAY, "INVALID_WHITELIST_SNAPSHOT", "whitelist source response is invalid", requestId);
                case CACHE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WHITELIST_CACHE_UNAVAILABLE", "whitelist cache is unavailable", requestId);
                case INVALID_CACHE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WHITELIST_CACHE_INVALID", "whitelist cache integrity check failed", requestId);
            };
        } catch (RuntimeException failure) {
            // Even an unexpected failure must not become the frontend's unconditional 2xx success message.
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "WHITELIST_SYNC_FAILED", "whitelist sync failed", requestId);
        }
    }

    private static ResponseEntity<ErrorResponseDto> error(HttpStatus status, String code, String message, String requestId) {
        return response(status).body(ErrorResponseDto.of(code, message, requestId));
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status).header("X-TMC-Dev-Only", "true").cacheControl(CacheControl.noStore());
    }

    public record Result(String status, long snapshotVersion) { }
}
