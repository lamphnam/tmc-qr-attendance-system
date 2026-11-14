package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;

@ExtendWith(OutputCaptureExtension.class)
class RestWhitelistSnapshotSourceTest {

    private static final String URL = "http://central.invalid/api/v1/kiosk/whitelist?factoryId=BD";
    private static final String API_KEY = "synthetic-source-api-key";
    private static final String SECRET = "  UTF-8: Đ-ß-🔐 / U2VjcmV0  ";
    private static final KioskIdentity IDENTITY = new KioskIdentity("BD-K01", "BD", "BD-G01");
    private static final Instant NOW = Instant.parse("2026-11-14T02:00:00.123Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private WhitelistHttpTransport transport;
    private RestWhitelistSnapshotSource source;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://central.invalid");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        transport = new WhitelistHttpTransport(client, IDENTITY, API_KEY, mapper);
        source = source(transport, IDENTITY);
    }

    @AfterEach
    void verifyRequestsAndSafeLogs(CapturedOutput output) {
        server.verify();
        assertThat(output).doesNotContain(API_KEY, SECRET);
    }

    private RestWhitelistSnapshotSource source(WhitelistHttpTransport transport, KioskIdentity identity) {
        return new RestWhitelistSnapshotSource(transport, new WhitelistEmployeeMapper(), identity, CLOCK);
    }

    private ObjectNode employee(String id, String status, int version) {
        return mapper.createObjectNode().put("employeeId", id).put("fullName", "Synthetic employee")
                .put("factoryId", "BD").put("department", "Assembly").put("sharedSecret", SECRET)
                .put("qrStatus", status).put("secretVersion", version).put("updatedAt", "2026-11-13T00:00:00Z");
    }

    private void response(String body, List<String> revisions) {
        DefaultResponseCreator response = withSuccess(body, MediaType.APPLICATION_JSON);
        if (!revisions.isEmpty()) {
            response.header("X-Whitelist-Version", revisions.toArray(String[]::new));
        }
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Kiosk-Api-Key", API_KEY)).andRespond(response);
    }

    private WhitelistSnapshot fetch() {
        return source.fetchSnapshot("BD-K01", "BD");
    }

    private static void safeFailure(Runnable action, Class<? extends Throwable> type) {
        assertThatThrownBy(action::run).isExactlyInstanceOf(type).hasNoCause().satisfies(failure -> {
            assertThat(failure.getSuppressed()).isEmpty();
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertThat(trace.toString()).doesNotContain(API_KEY, SECRET, "sharedSecret", "response-body-marker");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "42", "9223372036854775807"})
    void mapsOneCompleteResponseWithSourceRevisionAndUnmodifiedUtf8Secrets(String revision) {
        response("[" + employee("EMP-001", "ACTIVE", 3) + "," + employee("EMP-002", "REVOKED", 7) + "]",
                List.of(revision));

        WhitelistSnapshot snapshot = fetch();

        assertThat(snapshot.metadata().version()).isEqualTo(Long.parseLong(revision));
        assertThat(snapshot.metadata().kioskId()).isEqualTo("BD-K01");
        assertThat(snapshot.metadata().factoryId()).isEqualTo("BD");
        assertThat(snapshot.metadata().cachedAt()).isEqualTo(NOW);
        assertThat(snapshot.metadata().employeeCount()).isEqualTo(2);
        assertThat(snapshot.employees()).extracting(employee -> employee.qrStatus())
                .containsExactly(QrStatus.ACTIVE, QrStatus.REVOKED);
        assertThat(snapshot.employees()).extracting(employee -> employee.secretVersion()).containsExactly(3, 7);
        assertThat(snapshot.employees()).allSatisfy(employee -> {
            assertThat(employee.secretMaterial()).isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
            assertThat(employee.toString()).doesNotContain(SECRET, API_KEY);
        });
        byte[] material = snapshot.employees().getFirst().secretMaterial();
        material[0] = 0;
        assertThat(snapshot.employees().getFirst().secretMaterial()).isEqualTo(SECRET.getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.toString()).doesNotContain(SECRET, API_KEY);
        assertThat(source.toString()).doesNotContain(SECRET, API_KEY);
        // The mock expects exactly one GET: a second request for metadata would fail this test.
    }

    @Test
    void aVersionedEmptyArrayIsAnInitializedEmptySnapshotInTheConfiguredRequestScope() {
        response("[]", List.of("9"));

        WhitelistSnapshot snapshot = fetch();

        assertThat(snapshot.metadata().version()).isEqualTo(9);
        assertThat(snapshot.metadata().kioskId()).isEqualTo("BD-K01");
        assertThat(snapshot.metadata().factoryId()).isEqualTo("BD");
        assertThat(snapshot.metadata().employeeCount()).isZero();
        assertThat(snapshot.employees()).isEmpty();
    }

    static Stream<Arguments> invalidRevisions() {
        return Stream.of(List.<String>of(), List.of(""), List.of(" "), List.of("0"), List.of("-1"),
                List.of("+1"), List.of("1.0"), List.of("1e3"), List.of("abc"), List.of("١"),
                List.of("9223372036854775808"), List.of("-9223372036854775808"),
                List.of("7", "8"), List.of("7,8"), List.of("7,"), List.of(",7"), List.of("7", ""),
                List.of(SECRET)).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("invalidRevisions")
    void invalidOrAmbiguousRevisionNeverProducesAFakeSnapshot(List<String> revisions) {
        response("[]", revisions);
        safeFailure(() -> fetch(), IllegalArgumentException.class);
    }

    static Stream<Arguments> repeatedRevisions() {
        return Stream.of(List.of("7", "7"), List.of("7, 7"), List.of(" 7 "), List.of("007", "7"))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("repeatedRevisions")
    void acceptsOnlyNumericallyIdenticalRepeatedRevisions(List<String> revisions) {
        response("[]", revisions);
        assertThat(fetch().metadata().version()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"factory", "duplicate", "normalized-duplicate", "missing-secret", "blank-secret",
            "null-secret", "status", "version", "unicode"})
    void invalidRowsRejectTheWholeResponseRatherThanReturningAPartialSnapshot(String corruption) {
        ObjectNode row = employee("EMP-002", "ACTIVE", 2);
        switch (corruption) {
            case "factory" -> row.put("factoryId", "LA");
            case "duplicate" -> row.put("employeeId", "EMP-001");
            case "normalized-duplicate" -> row.put("employeeId", " EMP-001 ");
            case "missing-secret" -> row.remove("sharedSecret");
            case "blank-secret" -> row.put("sharedSecret", " ");
            case "null-secret" -> row.putNull("sharedSecret");
            case "status" -> row.put("qrStatus", "DISABLED");
            case "version" -> row.put("secretVersion", 0);
            case "unicode" -> row.put("sharedSecret", "unicode-marker");
            default -> throw new AssertionError("unknown test case");
        }
        String body = "[" + employee("EMP-001", "ACTIVE", 1) + "," + row + "]";
        response(body.replace("unicode-marker", "\\ud800"), List.of("10"));
        safeFailure(() -> fetch(), IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[", "[] []", "[null]", "<html>response-body-marker</html>"})
    void rejectsNonArrayMalformedAndIncompleteJson(String body) {
        response(body, List.of("10"));
        safeFailure(() -> fetch(), IllegalArgumentException.class);
    }

    @Test
    void malformedSensitiveBodyIsNotAttachedToTheDomainException() {
        response("[{\"sharedSecret\":\"" + SECRET + "\", response-body-marker]", List.of("10"));
        safeFailure(() -> fetch(), IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 501, 503, 500})
    void httpFailuresRespectTheDomainPortUnavailableContract(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .body(SECRET + " response-body-marker " + API_KEY));
        safeFailure(() -> fetch(), CentralUnavailableException.class);
    }

    @Test
    void timeoutIsUnavailableWithoutRetainingTheHttpException() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException(SECRET + API_KEY)));
        safeFailure(() -> fetch(), CentralUnavailableException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingCredentialFailsClosedWithoutMakingARequest(String key) {
        source = source(new WhitelistHttpTransport(client, IDENTITY, key, mapper), IDENTITY);
        safeFailure(() -> fetch(), CentralUnavailableException.class);
    }

    static Stream<Arguments> wrongScopes() {
        return Stream.of(Arguments.of(null, "BD"), Arguments.of("", "BD"), Arguments.of("OTHER", "BD"),
                Arguments.of("BD-K01", null), Arguments.of("BD-K01", ""), Arguments.of("BD-K01", "LA"));
    }

    @ParameterizedTest
    @MethodSource("wrongScopes")
    void rejectsCallerScopeOverridesBeforeAnyRequest(String kiosk, String factory) {
        safeFailure(() -> source.fetchSnapshot(kiosk, factory), IllegalArgumentException.class);
    }

    @Test
    void transportAndSourceMustUseTheSameFactoryEvenForAnEmptySnapshot() {
        source = source(transport, new KioskIdentity("LA-K01", "LA", "LA-G01"));
        response("[]", List.of("10"));
        safeFailure(() -> source.fetchSnapshot("LA-K01", "LA"), IllegalArgumentException.class);
    }

    @Test
    void transportResponseCopiesItsCollectionsAndRedactsEvenUntrustedHeaders() {
        response("[" + employee("EMP-001", "ACTIVE", 1) + "]", List.of(SECRET));
        var fetched = transport.fetchSnapshotResponse();
        List<String> revisions = new ArrayList<>(fetched.versionHeaders());
        List<WhitelistEmployeeWireDto> employees = new ArrayList<>(fetched.employees());
        var copy = new WhitelistHttpTransport.SnapshotResponse(fetched.factoryId(), revisions, employees);
        revisions.clear();
        employees.clear();
        assertThat(copy.versionHeaders()).hasSize(1);
        assertThat(copy.employees()).hasSize(1);
        assertThatThrownBy(() -> copy.employees().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(copy.toString()).doesNotContain(SECRET, API_KEY);
    }
}
