package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.infrastructure.config.RestClientConfig;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpException.Reason;

/** Real HTTP and the production client/timeouts; no Central or database is needed. */
@ExtendWith(OutputCaptureExtension.class)
class WhitelistHttpTransportTest {

    private static final String API_KEY = "synthetic-transport-credential";
    private static final String SECRET = "  synthetic UTF-8 material: Đ-ß-🔐  ";
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch waiting = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService serverPool;
    private RestClient client;
    private String baseUrl;
    private volatile int status = 200;
    private volatile String body;
    private volatile String contentType = "application/json";
    private volatile String stall;
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastKey;

    @BeforeEach
    void start() throws IOException {
        body = "[" + employee() + "]";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverPool = Executors.newCachedThreadPool();
        server.setExecutor(serverPool);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().getPath();
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastKey = exchange.getRequestHeaders().getFirst("X-Kiosk-Api-Key");
            try {
                if ("headers".equals(stall)) {
                    waiting.countDown();
                    release.await(8, TimeUnit.SECONDS);
                }
                if (contentType != null) {
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                }
                if (status == 302) {
                    exchange.getResponseHeaders().set("Location", baseUrl + "/credential-must-not-follow");
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status == 204 || status == 304 ? -1 : bytes.length);
                if ("body".equals(stall)) {
                    exchange.getResponseBody().write(bytes[0]);
                    exchange.getResponseBody().flush();
                    waiting.countDown();
                    release.await(8, TimeUnit.SECONDS);
                    exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                } else if (status != 204 && status != 304) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException disconnected) {
                // A read-timeout test closes the peer before this synthetic response is released.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = RestClientConfig.build(baseUrl, 500, 1000);
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        serverPool.shutdownNow();
    }

    private ObjectNode employee() {
        return mapper.createObjectNode().put("employeeId", "EMP-001").put("fullName", "Nhân viên thử nghiệm")
                .put("factoryId", "BD").put("department", "Assembly").put("sharedSecret", SECRET)
                .put("qrStatus", "ACTIVE").put("secretVersion", 1).put("updatedAt", "2026-11-14T00:00:00.123Z");
    }

    private WhitelistHttpTransport transport() {
        return new WhitelistHttpTransport(client, IDENTITY, API_KEY, mapper);
    }

    private static void safeFailure(Throwable failure, Reason reason) {
        assertThat(failure).isExactlyInstanceOf(WhitelistHttpException.class).hasNoCause();
        WhitelistHttpException error = (WhitelistHttpException) failure;
        assertThat(error.reason()).isEqualTo(reason);
        assertThat(error.getSuppressed()).isEmpty();
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(API_KEY, SECRET);
    }

    private static void safeFailure(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).satisfies(failure -> safeFailure(failure, reason));
    }

    @Test
    void getsConfiguredFactoryAndCredentialAndPreservesEmployeeValuesWithoutDecoding() {
        List<WhitelistEmployeeWireDto> result = transport().fetchEmployees();

        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastPath).isEqualTo("/api/v1/kiosk/whitelist");
        assertThat(lastQuery).isEqualTo("factoryId=BD");
        assertThat(lastKey).isEqualTo(API_KEY);
        assertThat(requests).hasValue(1);
        assertThat(result).hasSize(1);
        WhitelistEmployeeWireDto row = result.getFirst();
        assertThat(row.employeeId()).isEqualTo("EMP-001");
        assertThat(row.fullName()).isEqualTo("Nhân viên thử nghiệm");
        assertThat(row.factoryId()).isEqualTo("BD");
        assertThat(row.department()).isEqualTo("Assembly");
        assertThat(row.sharedSecret()).isEqualTo(SECRET);
        assertThat(row.qrStatus()).isEqualTo("ACTIVE");
        assertThat(row.secretVersion()).isEqualTo(1);
        assertThat(row.updatedAt()).isEqualTo(Instant.parse("2026-11-14T00:00:00.123Z"));
        assertThat(row.toString()).isEqualTo("WhitelistEmployeeWireDto[<redacted>]");
        assertThat(transport().toString()).isEqualTo("WhitelistHttpTransport[apiKey=<redacted>]");
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsValidEmptyArrayWithoutInventingSnapshotMetadata() {
        body = "[]";
        assertThat(transport().fetchEmployees()).isEmpty();
        assertThat(lastQuery).isEqualTo("factoryId=BD");
    }

    @Test
    void returnsRevokedEmployeesAndIgnoresUnrecognizedNonRequiredFields() {
        ObjectNode revoked = employee().put("employeeId", "EMP-002").put("qrStatus", "REVOKED")
                .put("secretVersion", 2).put("futureInformation", "not-used");
        body = "[" + employee() + "," + revoked + "]";
        assertThat(transport().fetchEmployees()).extracting(WhitelistEmployeeWireDto::qrStatus)
                .containsExactly("ACTIVE", "REVOKED");
    }

    @Test
    void credentialIsRequestLocalAndNeverAddedToTheSharedClientDefaults() {
        transport().fetchEmployees();
        assertThat(lastKey).isEqualTo(API_KEY);

        client.get().uri("/api/dev/attendance/sync").exchange((request, response) -> response.getStatusCode().value());

        assertThat(lastPath).isEqualTo("/api/dev/attendance/sync");
        assertThat(lastKey).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingCredentialFailsClosedBeforeAnyRequest(String key) {
        safeFailure(() -> new WhitelistHttpTransport(client, IDENTITY, key, mapper).fetchEmployees(), Reason.MISSING_CREDENTIAL);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "BD&factoryId=LA", "BD/LA", "BD\n"})
    void invalidFactoryConfigurationIsNeverUsedAsAQueryOverride(String factory) {
        // A trailing newline is stripped consistently with the existing trusted identity normalization.
        if (factory.equals("BD\n")) {
            new WhitelistHttpTransport(client, new KioskIdentity("BD-K01", factory, "G"), API_KEY, mapper).fetchEmployees();
            assertThat(lastQuery).isEqualTo("factoryId=BD");
            return;
        }
        safeFailure(() -> new WhitelistHttpTransport(client, new KioskIdentity("BD-K01", factory, "G"), API_KEY, mapper)
                .fetchEmployees(), Reason.INVALID_CONFIGURATION);
        assertThat(requests).hasValue(0);
    }

    @Test
    void invalidCredentialHeaderIsRejectedWithoutExposingIt() {
        safeFailure(() -> new WhitelistHttpTransport(client, IDENTITY, API_KEY + "\r\nInjected: value", mapper)
                .fetchEmployees(), Reason.INVALID_CONFIGURATION);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 302, 304, 400, 403, 500, 501, 503, 504})
    void non200ResponseNeverReturnsDataFollowsRedirectsOrRetries(int responseStatus, CapturedOutput output) {
        status = responseStatus;
        body = "sensitive upstream failure: " + SECRET;
        assertThatThrownBy(() -> transport().fetchEmployees()).satisfies(failure -> {
            safeFailure(failure, Reason.HTTP_ERROR);
            assertThat(((WhitelistHttpException) failure).httpStatus()).hasValue(responseStatus);
        });
        assertThat(requests).hasValue(1);
        assertThat(output).doesNotContain(API_KEY, SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"employeeId", "fullName", "factoryId", "department", "sharedSecret", "qrStatus", "secretVersion", "updatedAt"})
    void missingRequiredFieldRejectsWholeArray(String field, CapturedOutput output) {
        ObjectNode invalid = employee();
        invalid.remove(field);
        body = "[" + employee() + "," + invalid + "]";
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
        assertThat(output).doesNotContain(API_KEY, SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"employeeId", "fullName", "factoryId", "department", "sharedSecret", "qrStatus", "secretVersion", "updatedAt"})
    void nullRequiredFieldRejectsWholeArray(String field) {
        body = "[" + employee().putNull(field) + "]";
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-factory", "unknown-status", "blank-id", "blank-secret", "string-version", "zero-version",
            "fractional-version", "overflow-version", "invalid-time", "numeric-secret", "duplicate-id", "normalized-duplicate-id"})
    void rejectsInvalidRowsWithoutDroppingThemOrReturningAPartialList(String corruption) {
        ObjectNode row = employee();
        switch (corruption) {
            case "wrong-factory" -> row.put("factoryId", "LA");
            case "unknown-status" -> row.put("qrStatus", "DISABLED");
            case "blank-id" -> row.put("employeeId", " ");
            case "blank-secret" -> row.put("sharedSecret", " ");
            case "string-version" -> row.put("secretVersion", "1");
            case "zero-version" -> row.put("secretVersion", 0);
            case "fractional-version" -> row.put("secretVersion", 1.5);
            case "overflow-version" -> row.put("secretVersion", Long.MAX_VALUE);
            case "invalid-time" -> row.put("updatedAt", "invalid-instant");
            case "numeric-secret" -> row.put("sharedSecret", 123);
            case "normalized-duplicate-id" -> row.put("employeeId", " EMP-001 ");
            case "duplicate-id" -> { }
            default -> throw new AssertionError("unknown case");
        }
        body = "[" + employee() + "," + row + "]";
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"employees\":[]}", "[null]", "[1]", "[] []", "[", "<html>not json</html>"})
    void rejectsMissingNonArrayMalformedOrTrailingJson(String response) {
        body = response;
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
    }

    @Test
    void malformedJsonContainingSecretAndDuplicateJsonFieldsExposeOnlySafeDiagnostics(CapturedOutput output) {
        body = "[{\"sharedSecret\":\"" + SECRET + "\", BROKEN}]";
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
        body = "[{\"sharedSecret\":\"" + SECRET + "\",\"sharedSecret\":\"other\"}]";
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
        assertThat(output).doesNotContain(API_KEY, SECRET);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"text/html", "text/plain", "invalid/media/type"})
    void rejectsMissingOrNonJsonContentType(String type) {
        contentType = type;
        safeFailure(() -> transport().fetchEmployees(), Reason.INVALID_RESPONSE);
    }

    @Test
    void unreachableServiceReturnsSafeUnavailableError() {
        server.stop(0);
        safeFailure(() -> transport().fetchEmployees(), Reason.UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "body"})
    void configuredReadTimeoutBoundsWaitingForHeadersAndBody(String phase) throws Exception {
        stall = phase;
        WhitelistHttpTransport transport = new WhitelistHttpTransport(RestClientConfig.build(baseUrl, 500, 200),
                IDENTITY, API_KEY, mapper);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var response = pool.submit(transport::fetchEmployees);
            assertThat(waiting.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> response.get(3, TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,
                    failure -> safeFailure(failure.getCause(), Reason.UNAVAILABLE));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
