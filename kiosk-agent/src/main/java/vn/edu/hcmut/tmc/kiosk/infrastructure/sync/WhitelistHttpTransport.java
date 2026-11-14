package vn.edu.hcmut.tmc.kiosk.infrastructure.sync;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.infrastructure.sync.WhitelistHttpException.Reason;

/**
 * Explicit, single-request transport for Central's current employee array. It owns no cache or source revision,
 * implements no domain source port, and never contacts Central at construction/startup. Offline orchestration
 * remains in Application; the shared client supplies connect/read timeouts.
 */
public final class WhitelistHttpTransport {

    private static final String PATH = "/api/v1/kiosk/whitelist";
    private final RestClient client;
    private final String factoryId;
    private final String apiKey;
    private final ObjectReader reader;

    public WhitelistHttpTransport(RestClient client, KioskIdentity identity, String apiKey, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(identity, "identity");
        this.factoryId = identity.factoryId() == null ? null : identity.factoryId().strip();
        this.apiKey = apiKey;
        this.reader = Objects.requireNonNull(mapper, "mapper").readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /**
     * Returns an immutable, fully validated list, including a valid empty array. The secret String is preserved
     * exactly, without decoding/normalization; per-employee version/timestamp are not a snapshot revision.
     * Every row must match the configured factory; the dev API cannot prove kiosk authorization or empty-list scope.
     *
     * @throws WhitelistHttpException for missing configuration, non-200 status, unavailable HTTP or invalid data;
     *                                only a fixed reason and optional HTTP status escape, never an upstream cause
     */
    public List<WhitelistEmployeeWireDto> fetchEmployees() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new WhitelistHttpException(Reason.MISSING_CREDENTIAL);
        }
        if (factoryId == null || !factoryId.matches("[A-Za-z0-9_-]{1,32}")
                || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new WhitelistHttpException(Reason.INVALID_CONFIGURATION);
        }
        try {
            return client.get().uri(uri -> uri.path(PATH).queryParam("factoryId", factoryId).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Kiosk-Api-Key", apiKey)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            throw WhitelistHttpException.httpError(status);
                        }
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                            throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
                        }
                        try {
                            return parse(reader.readTree(response.getBody()));
                        } catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException
                                | DateTimeException invalidData) {
                            throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
                        } catch (IOException unavailable) {
                            throw new WhitelistHttpException(Reason.UNAVAILABLE);
                        }
                    });
        } catch (WhitelistHttpException safeFailure) {
            throw safeFailure;
        } catch (IllegalArgumentException invalidData) {
            // Exception text/causes from HTTP and Jackson can include secrets or body excerpts.
            throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
        } catch (RestClientException unavailable) {
            throw new WhitelistHttpException(Reason.UNAVAILABLE);
        }
    }

    private List<WhitelistEmployeeWireDto> parse(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
        }
        List<WhitelistEmployeeWireDto> employees = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode row : root) {
            if (!row.isObject()) {
                throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
            }
            JsonNode version = row.get("secretVersion");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
                throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
            }
            WhitelistEmployeeWireDto employee = new WhitelistEmployeeWireDto(text(row, "employeeId"),
                    text(row, "fullName"), text(row, "factoryId"), text(row, "department"), text(row, "sharedSecret"),
                    text(row, "qrStatus"), version.intValue(), Instant.parse(text(row, "updatedAt")));
            if (!factoryId.equals(employee.factoryId()) || !ids.add(employee.employeeId().strip())) {
                throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
            }
            employees.add(employee);
        }
        return List.copyOf(employees);
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        if (value == null || !value.isTextual()) {
            throw new WhitelistHttpException(Reason.INVALID_RESPONSE);
        }
        return value.textValue();
    }

    @Override
    public String toString() {
        return "WhitelistHttpTransport[apiKey=<redacted>]";
    }
}
