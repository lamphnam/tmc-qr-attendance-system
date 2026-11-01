package vn.edu.hcmut.tmc.kiosk.presentation.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.edu.hcmut.tmc.kiosk.application.usecase.CentralSyncSwitch;
import vn.edu.hcmut.tmc.kiosk.application.usecase.QueueQueryService;
import vn.edu.hcmut.tmc.kiosk.application.usecase.QueueSyntheticEventUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncPendingEventsUseCase;
import vn.edu.hcmut.tmc.kiosk.application.usecase.SyncRunReport;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.DevDtos;
import vn.edu.hcmut.tmc.kiosk.presentation.dto.QueuedEventDto;

/**
 * DEV-ONLY vertical-slice fixtures (P1). Present only under the {@code dev} profile and <strong>unauthenticated</strong>.
 * The events created here are synthetic (no QR / TOTP / whitelist check) and are flagged {@code devFixture}.
 */
@RestController
@Profile("dev")
@RequestMapping("/api/dev/kiosk")
public class DevQueueController {

    static final String DEV_HEADER = "X-TMC-Dev-Only";

    private final QueueSyntheticEventUseCase queueSyntheticEvent;
    private final SyncPendingEventsUseCase syncPendingEvents;
    private final QueueQueryService queueQuery;
    private final CentralSyncSwitch centralSync;

    public DevQueueController(QueueSyntheticEventUseCase queueSyntheticEvent, SyncPendingEventsUseCase syncPendingEvents,
                              QueueQueryService queueQuery, CentralSyncSwitch centralSync) {
        this.queueSyntheticEvent = queueSyntheticEvent;
        this.syncPendingEvents = syncPendingEvents;
        this.queueQuery = queueQuery;
        this.centralSync = centralSync;
    }

    /** Commits a synthetic PENDING event to SQLite, then sends the open command to the local turnstile. */
    @PostMapping("/synthetic-event")
    public ResponseEntity<QueuedEventDto> queueSyntheticEvent(@RequestBody(required = false) DevDtos.SyntheticEventRequest body) {
        Direction direction = body == null ? null : parseDirection(body.direction());
        var event = queueSyntheticEvent.execute(body == null ? null : body.employeeId(), body == null ? null : body.gateId(), direction);
        return ResponseEntity.status(HttpStatus.CREATED).header(DEV_HEADER, "true").body(QueuedEventDto.from(event));
    }

    @GetMapping("/queue")
    public ResponseEntity<DevDtos.QueueSnapshot> queue(@RequestParam(defaultValue = "50") int limit) {
        QueueQueryService.QueueSnapshot snapshot = queueQuery.snapshot(limit);
        List<QueuedEventDto> events = snapshot.recent().stream().map(QueuedEventDto::from).toList();
        return devOk(new DevDtos.QueueSnapshot(snapshot.counts(), events));
    }

    /** Runs one sync pass now (the scheduler does the same on a timer). */
    @PostMapping("/sync/run")
    public ResponseEntity<SyncRunReport> runSync() {
        return devOk(syncPendingEvents.run());
    }

    @GetMapping("/sync/state")
    public ResponseEntity<DevDtos.SyncState> syncState() {
        return devOk(new DevDtos.SyncState(centralSync.isDisabled()));
    }

    /** Simulates losing the WAN: stops ONLY kiosk -> central sync (D05). The local turnstile keeps working. */
    @PutMapping("/sync/offline")
    public ResponseEntity<DevDtos.SyncState> setOffline(@RequestBody DevDtos.OfflineRequest body) {
        if (body == null || body.disabled() == null) {
            throw new IllegalArgumentException("body must be {\"disabled\": true|false}");
        }
        centralSync.setDisabled(body.disabled());
        return devOk(new DevDtos.SyncState(centralSync.isDisabled()));
    }

    private static Direction parseDirection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Direction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("direction must be IN or OUT");
        }
    }

    private static <T> ResponseEntity<T> devOk(T body) {
        return ResponseEntity.ok().header(DEV_HEADER, "true").body(body);
    }
}
