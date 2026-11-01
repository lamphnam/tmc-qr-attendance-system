package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import vn.edu.hcmut.tmc.kiosk.config.KioskProperties;
import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.EventSyncResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncOutcome;
import vn.edu.hcmut.tmc.kiosk.domain.port.IKioskSyncAPI;

/**
 * Sends a batch to central through the gateway. Wire format is the [DRAFT] of docs/API-CONTRACTS.md section 5.
 *
 * <p>Transport rule: ANY failure of the request as a whole (refused, timeout, 4xx, 5xx, unreadable body) becomes a
 * {@link CentralUnavailableException}, i.e. "assume nothing was committed, retry the same ids". Permanent rejection is
 * only ever concluded from a per-event {@code REJECTED_PERMANENT} result, so a misconfigured route can never silently
 * mark the whole queue FAILED.
 */
@Component
public class RestAttendanceSyncClient implements IKioskSyncAPI {

    private final RestClient client;
    private final String syncPath;

    public RestAttendanceSyncClient(@Qualifier("centralRestClient") RestClient client, KioskProperties properties) {
        this.client = client;
        this.syncPath = properties.central().syncPath();
    }

    @Override
    public List<EventSyncResult> syncBatch(String kioskId, List<LocalAttendanceEvent> events) {
        SyncRequest request = new SyncRequest(kioskId, events.stream().map(RestAttendanceSyncClient::toWire).toList());
        try {
            SyncResponse response = client.post()
                    .uri(syncPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", requestKey(events))
                    .body(request)
                    .retrieve()
                    .body(SyncResponse.class);
            if (response == null || response.results() == null) {
                throw new CentralUnavailableException("central returned an empty sync response");
            }
            return toDomain(response.results());
        } catch (RestClientResponseException e) {
            throw new CentralUnavailableException("central answered HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new CentralUnavailableException("central unreachable (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Same ids in the same order always produce the same key, so a retried batch is recognisably the same request. */
    static String requestKey(List<LocalAttendanceEvent> events) {
        StringBuilder ids = new StringBuilder();
        events.forEach(e -> ids.append(e.attendanceId()).append(','));
        return UUID.nameUUIDFromBytes(ids.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static WireEvent toWire(LocalAttendanceEvent e) {
        return new WireEvent(e.attendanceId().toString(), e.employeeId(), e.factoryId(), e.gateId(),
                e.direction().name(), e.occurredAt().toString(), e.scanResult().name());
    }

    private static List<EventSyncResult> toDomain(List<WireResult> wire) {
        List<EventSyncResult> results = new ArrayList<>(wire.size());
        for (WireResult item : wire) {
            try {
                results.add(new EventSyncResult(UUID.fromString(item.attendanceId()),
                        SyncOutcome.valueOf(item.status()), item.reason()));
            } catch (IllegalArgumentException | NullPointerException unreadable) {
                // An answer we cannot interpret is treated as "not acknowledged": the event stays PENDING.
            }
        }
        return results;
    }

    record WireEvent(String attendanceId, String employeeId, String factoryId, String gateId,
                     String direction, String occurredAt, String scanResult) {
    }

    record SyncRequest(String kioskId, List<WireEvent> events) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResult(String attendanceId, String status, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SyncResponse(List<WireResult> results) {
    }
}
