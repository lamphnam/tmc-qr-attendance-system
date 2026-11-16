package vn.edu.hcmut.tmc.reporting.infrastructure.hr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryOutcome;
import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;

/**
 * Channel {@code rest} against a loopback HTTP receiver that behaves like {@code mock-hr-payroll-api} (upsert by
 * attendanceId, {@code duplicateBatch}, failure injection storing nothing). The real Node mock is exercised through the
 * gateway by {@code tests/integration/reporting.test.mjs}.
 */
class RestHrPayrollGatewayTest extends HrPayrollGatewayContract {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final Map<String, Boolean> batches = new ConcurrentHashMap<>();
    private final List<UUID> records = new CopyOnWriteArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private volatile int failureStatus;
    private volatile String rawAnswer;
    private volatile long delayMs;

    @BeforeEach
    void startReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RestHrPayrollGateway.BATCH_PATH, this::receive);
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private void receive(HttpExchange exchange) throws IOException {
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            requests.add(body);
            idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (failureStatus != 0) {
                reply(exchange, failureStatus, "{\"code\":\"HR_UNAVAILABLE\"}");
                return;
            }
            if (rawAnswer != null) {
                reply(exchange, 200, rawAnswer);
                return;
            }
            String batchUuid = body.get("batchUuid").asText();
            boolean duplicate = batches.putIfAbsent(batchUuid, true) != null;
            Set<UUID> known = new HashSet<>(records);
            for (JsonNode record : body.get("records")) {
                UUID id = UUID.fromString(record.get("attendanceId").asText());
                if (known.add(id)) {
                    records.add(id);
                }
            }
            reply(exchange, 200, "{\"batchUuid\":\"" + batchUuid + "\",\"status\":\"ACCEPTED\",\"duplicateBatch\":" + duplicate + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    protected IHrPayrollGateway channel() {
        return new RestHrPayrollGateway(baseUrl(), 1000, 2000);
    }

    @Override
    protected void breakReceiver() {
        failureStatus = 503;
    }

    @Override
    protected void repairReceiver() {
        failureStatus = 0;
    }

    @Override
    protected List<UUID> heldByReceiver() {
        return new ArrayList<>(records);
    }

    @Override
    protected boolean receiverConfirms() {
        return true;
    }

    // ---- REST-specific mapping (D09 / B09)

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 408, 429})
    void serverErrorsTimeoutsAndThrottlingAreRetryable(int status) {
        failureStatus = status;

        DeliveryResult result = channel().send(batch("2025-01", 1));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.detail()).isEqualTo("HTTP " + status);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void otherClientErrorsAreRejectedBecauseAResendCannotHelp(int status) {
        failureStatus = status;

        assertThat(channel().send(batch("2025-01", 1)).outcome()).isEqualTo(DeliveryOutcome.REJECTED);
    }

    @Test
    void aSlowReceiverTimesOutAsRetryable() {
        delayMs = 1500;
        IHrPayrollGateway impatient = new RestHrPayrollGateway(baseUrl(), 500, 300);

        DeliveryResult result = impatient.send(batch("2025-01", 1));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
        assertThat(result.detail()).startsWith("HR unreachable");
    }

    @Test
    void aRefusedConnectionIsRetryable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        DeliveryResult result = new RestHrPayrollGateway("http://127.0.0.1:" + closedPort, 500, 500).send(batch("2025-01", 1));

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void anAnswerThatDoesNotConfirmTheBatchIsNeverTakenAsAcknowledged() {
        rawAnswer = "{\"status\":\"MAYBE\"}";

        assertThat(channel().send(batch("2025-01", 1)).outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
    }

    @Test
    void theRequestCarriesTheStableBatchIdPeriodAndEveryRecord() {
        PayrollBatch batch = batch("2025-10", 2);

        channel().send(batch);

        JsonNode request = requests.get(0);
        assertThat(request.get("batchUuid").asText()).isEqualTo(batch.batchUuid().toString());
        assertThat(request.get("period").asText()).isEqualTo("2025-10");
        assertThat(request.get("records")).hasSize(2);
        assertThat(request.get("records").get(0).get("employeeId").asText()).isEqualTo("EMP-CONTRACT-0");
        assertThat(idempotencyKeys).containsExactly(batch.batchUuid().toString());
    }
}
