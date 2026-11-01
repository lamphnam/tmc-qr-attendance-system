package vn.edu.hcmut.tmc.kiosk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whole kiosk in one JVM: real Spring wiring, real SQLite file, real HTTP to a local stand-in for the mock turnstile
 * and for central. Walks the offline -> PENDING -> reconnect -> SYNCED story through the dev endpoints.
 */
@SpringBootTest(properties = {"kiosk.sync.enabled=false", "management.endpoint.health.show-details=always"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class KioskAgentApplicationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger turnstileCommands = new AtomicInteger();
    private static final List<String> centralRequests = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB;
    private static final Path DB_DIR;

    static {
        try {
            DB_DIR = Files.createTempDirectory("kiosk-app-test");
            STUB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB.createContext("/gates/", exchange -> {
                turnstileCommands.incrementAndGet();
                reply(exchange, 200, "{\"result\":\"COMMAND_ACCEPTED\"}");
            });
            STUB.createContext("/api/dev/attendance/sync", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                centralRequests.add(body);
                StringBuilder results = new StringBuilder();
                for (JsonNode event : JSON.readTree(body).get("events")) {
                    results.append(results.length() == 0 ? "" : ",")
                            .append("{\"attendanceId\":\"").append(event.get("attendanceId").asText()).append("\",\"status\":\"STORED\"}");
                }
                reply(exchange, 200, "{\"results\":[" + results + "]}");
            });
            STUB.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String stubUrl = "http://127.0.0.1:" + STUB.getAddress().getPort();
        registry.add("kiosk.sqlite.path", () -> DB_DIR.resolve("kiosk.db").toString());
        registry.add("kiosk.central.base-url", () -> stubUrl);
        registry.add("kiosk.turnstile.base-url", () -> stubUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void backOnline() throws Exception {
        mvc.perform(put("/api/dev/kiosk/sync/offline").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":false}"))
                .andExpect(status().isOk());
    }

    private String queueSyntheticEvent() throws Exception {
        String body = mvc.perform(post("/api/dev/kiosk/synthetic-event").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"EMP002\",\"gateId\":\"BD-G01\",\"direction\":\"in\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-TMC-Dev-Only", "true"))
                .andExpect(jsonPath("$.syncStatus").value("PENDING"))
                .andExpect(jsonPath("$.gateCommandStatus").value("SENT"))
                .andExpect(jsonPath("$.devFixture").value(true))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("attendanceId").asText();
    }

    private String syncStatusOf(String attendanceId) throws Exception {
        String body = mvc.perform(get("/api/dev/kiosk/queue?limit=200")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode event : JSON.readTree(body).get("events")) {
            if (attendanceId.equals(event.get("attendanceId").asText())) {
                return event.get("syncStatus").asText();
            }
        }
        throw new AssertionError("event " + attendanceId + " is not in the queue");
    }

    @Test
    void healthProvesTheSqliteFileIsOpenReadableAndInWalMode() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.sqliteQueue.status").value("UP"))
                .andExpect(jsonPath("$.components.sqliteQueue.details.journalMode").value("wal"))
                .andExpect(jsonPath("$.components.sqliteQueue.details.synchronous").value("FULL"));
    }

    @Test
    void theRealScanEndpointAnswersNotImplementedAndNeverEchoesTheTotp() throws Exception {
        String response = mvc.perform(post("/api/local/scan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateId\":\"BD-G01\",\"direction\":\"IN\",\"qrPayload\":{\"empId\":\"EMP001\",\"totp\":\"482910\",\"ts\":1761152000,\"ver\":1}}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("482910");
    }

    @Test
    void scanWhileOfflineStaysPendingThenSyncsExactlyOnceAfterReconnect() throws Exception {
        mvc.perform(put("/api/dev/kiosk/sync/offline").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"))
                .andExpect(jsonPath("$.centralSyncDisabled").value(true));
        int turnstileBefore = turnstileCommands.get();
        int centralBefore = centralRequests.size();

        // offline: the gate command still goes out locally, the event is durably PENDING
        String attendanceId = queueSyntheticEvent();
        assertThat(turnstileCommands.get()).as("local turnstile works while central sync is off").isEqualTo(turnstileBefore + 1);

        mvc.perform(post("/api/dev/kiosk/sync/run")).andExpect(jsonPath("$.skippedReason").value("CENTRAL_SYNC_DISABLED"));
        assertThat(centralRequests).as("central was not contacted while offline").hasSize(centralBefore);
        assertThat(syncStatusOf(attendanceId)).isEqualTo("PENDING");

        // back online: one sync pass acknowledges the event
        mvc.perform(put("/api/dev/kiosk/sync/offline").contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":false}"));
        mvc.perform(post("/api/dev/kiosk/sync/run")).andExpect(status().isOk()).andExpect(jsonPath("$.synced").value(1));
        assertThat(syncStatusOf(attendanceId)).isEqualTo("SYNCED");
        assertThat(centralRequests).hasSize(centralBefore + 1);
        assertThat(centralRequests.get(centralBefore)).contains(attendanceId);

        // nothing left to send: a second pass must not re-send the event
        mvc.perform(post("/api/dev/kiosk/sync/run")).andExpect(jsonPath("$.attempted").value(0));
        assertThat(centralRequests).hasSize(centralBefore + 1);
    }

    @Test
    void badInputIsAnErrorShapeNotAStackTrace() throws Exception {
        mvc.perform(post("/api/dev/kiosk/synthetic-event").contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mvc.perform(put("/api/dev/kiosk/sync/offline").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
