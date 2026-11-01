package vn.edu.hcmut.tmc.kiosk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.edu.hcmut.tmc.kiosk.config.KioskProperties;
import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.EventSyncResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.SyncOutcome;
import vn.edu.hcmut.tmc.kiosk.infrastructure.config.RestClientConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.RestAttendanceSyncClient;
import vn.edu.hcmut.tmc.kiosk.infrastructure.turnstile.EthernetTurnstileAdapter;

/** Real HTTP over loopback against a throw-away JDK server: no mocking of the HTTP client itself. */
class HttpAdaptersTest {

    private static final String SYNC_PATH = "/api/dev/attendance/sync";
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");

    private HttpServer server;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile String lastPath;
    private volatile String lastBody;
    private volatile String lastIdempotencyKey;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastIdempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static KioskProperties properties(String url) {
        return new KioskProperties("BD-K01", "BD", "BD-G01", new KioskProperties.Sqlite("unused"),
                new KioskProperties.Central(url, SYNC_PATH, false, 500, 1000),
                new KioskProperties.Turnstile(url, 500),
                new KioskProperties.Sync(false, 5000, 20, 2000, 60000));
    }

    private static RestAttendanceSyncClient syncClient(String url) {
        return new RestAttendanceSyncClient(RestClientConfig.build(url, 500, 1000), properties(url));
    }

    private static LocalAttendanceEvent event(UUID id) {
        Instant at = Instant.parse("2026-11-01T01:20:00.123Z");
        return LocalAttendanceEvent.newPending(id, "EMP001", "BD", "BD-G01", Direction.IN, at, ScanResult.ALLOWED, true, at);
    }

    // ---------------------------------------------------------------- sync client

    @Test
    void syncClientSendsTheStableIdAndMapsEachPerEventResult() {
        UUID stored = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        responseBody = """
                {"results":[{"attendanceId":"%s","status":"STORED"},
                            {"attendanceId":"%s","status":"REJECTED_PERMANENT","reason":"bad payload"}],
                 "retryableIds":[],"rejectedIds":["%s"]}""".formatted(stored, rejected, rejected);

        List<EventSyncResult> results = syncClient(baseUrl).syncBatch("BD-K01", List.of(event(stored), event(rejected)));

        assertThat(results).containsExactly(
                new EventSyncResult(stored, SyncOutcome.STORED, null),
                new EventSyncResult(rejected, SyncOutcome.REJECTED_PERMANENT, "bad payload"));
        assertThat(lastPath).isEqualTo(SYNC_PATH);
        assertThat(lastBody).contains("\"kioskId\":\"BD-K01\"").contains("\"attendanceId\":\"" + stored + "\"")
                .contains("\"direction\":\"IN\"").contains("\"occurredAt\":\"2026-11-01T01:20:00.123Z\"");
    }

    @Test
    void syncRequestKeyIsStableForTheSameBatchSoRetriesLookIdentical() {
        UUID id = UUID.randomUUID();
        responseBody = "{\"results\":[]}";

        syncClient(baseUrl).syncBatch("BD-K01", List.of(event(id)));
        String first = lastIdempotencyKey;
        syncClient(baseUrl).syncBatch("BD-K01", List.of(event(id)));

        assertThat(first).isNotBlank().isEqualTo(lastIdempotencyKey);
    }

    @Test
    void syncClientTreatsServerErrorsAsCentralUnavailableNotAsRejection() {
        status = 500;
        responseBody = "{\"code\":\"BOOM\"}";

        assertThatThrownBy(() -> syncClient(baseUrl).syncBatch("BD-K01", List.of(event(UUID.randomUUID()))))
                .isInstanceOf(CentralUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void syncClientTreatsABadRequestAsCentralUnavailableSoNothingIsMarkedFailedByAMisroute() {
        status = 404;

        assertThatThrownBy(() -> syncClient(baseUrl).syncBatch("BD-K01", List.of(event(UUID.randomUUID()))))
                .isInstanceOf(CentralUnavailableException.class);
    }

    @Test
    void syncClientTreatsAnUnreachableCentralAsCentralUnavailable() {
        assertThatThrownBy(() -> syncClient("http://127.0.0.1:1").syncBatch("BD-K01", List.of(event(UUID.randomUUID()))))
                .isInstanceOf(CentralUnavailableException.class);
    }

    @Test
    void anAnswerItCannotInterpretIsNotAnAcknowledgement() {
        responseBody = "{\"results\":[{\"attendanceId\":\"" + UUID.randomUUID() + "\",\"status\":\"WHATEVER\"},"
                + "{\"attendanceId\":\"not-a-uuid\",\"status\":\"STORED\"}]}";

        assertThat(syncClient(baseUrl).syncBatch("BD-K01", List.of(event(UUID.randomUUID())))).isEmpty();
    }

    // ---------------------------------------------------------------- turnstile adapter

    private EthernetTurnstileAdapter turnstile(String url) {
        return new EthernetTurnstileAdapter(RestClientConfig.build(url, 500, 500), IDENTITY);
    }

    @Test
    void turnstileAcceptedCommandIsSentButNeverClaimsAPhysicalOpen() {
        responseBody = "{\"result\":\"COMMAND_ACCEPTED\"}";

        GateCommandResult result = turnstile(baseUrl).openGate("BD-G01");

        assertThat(result.status()).isEqualTo(GateCommandStatus.SENT);
        assertThat(result.detail()).contains("NOT confirmed");
        assertThat(lastPath).isEqualTo("/gates/BD-G01/open");
        assertThat(lastBody).contains("\"kioskId\":\"BD-K01\"");
    }

    @Test
    void turnstileErrorStatusBecomesFailedWithoutThrowing() {
        status = 503;

        GateCommandResult result = turnstile(baseUrl).openGate("BD-G01");

        assertThat(result.status()).isEqualTo(GateCommandStatus.FAILED);
        assertThat(result.detail()).contains("503");
    }

    @Test
    void unreachableTurnstileBecomesFailedWithoutThrowing() {
        GateCommandResult result = turnstile("http://127.0.0.1:1").openGate("BD-G01");

        assertThat(result.status()).isEqualTo(GateCommandStatus.FAILED);
    }
}
