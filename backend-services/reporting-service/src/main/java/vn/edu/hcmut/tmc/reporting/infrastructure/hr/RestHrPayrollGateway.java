package vn.edu.hcmut.tmc.reporting.infrastructure.hr;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import vn.edu.hcmut.tmc.reporting.domain.model.DeliveryResult;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollBatch;
import vn.edu.hcmut.tmc.reporting.domain.model.PayrollRecord;
import vn.edu.hcmut.tmc.reporting.domain.port.IHrPayrollGateway;

/**
 * Channel {@code rest} (default): {@code POST /mock/hr/attendance-batches} on the HR/Payroll API (DRAFT contract,
 * API-CONTRACTS section 7). The receiver answers synchronously, so a readable {@code ACCEPTED} is {@code ACKNOWLEDGED}.
 *
 * <p>Failure mapping (D09 / B09): 5xx, 408, 429, refused connections and timeouts are retryable; any other 4xx means
 * the receiver refuses this content, so resending it cannot help. A 2xx whose body cannot be read is treated as "not
 * confirmed" and retried, which is safe because the receiver dedupes by batch and attendance id.
 */
@Component
@ConditionalOnProperty(name = "tmc.hr-export.channel", havingValue = "rest", matchIfMissing = true)
public class RestHrPayrollGateway implements IHrPayrollGateway {

    static final String BATCH_PATH = "/mock/hr/attendance-batches";

    private final RestClient client;

    public RestHrPayrollGateway(@Value("${tmc.hr-api.base-url}") String baseUrl,
                                @Value("${tmc.hr-api.connect-timeout-ms:2000}") int connectTimeoutMs,
                                @Value("${tmc.hr-api.read-timeout-ms:10000}") int readTimeoutMs) {
        // HTTP/1.1 forced: the JDK client's h2c upgrade attempt confuses simple HTTP servers such as the Node mock.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public DeliveryResult send(PayrollBatch batch) {
        WireBatch body = new WireBatch(batch.batchUuid().toString(), batch.period().label(),
                batch.records().stream().map(RestHrPayrollGateway::toWire).toList());
        try {
            WireAck ack = client.post()
                    .uri(BATCH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", batch.batchUuid().toString())
                    .body(body)
                    .retrieve()
                    .body(WireAck.class);
            if (ack == null || !"ACCEPTED".equals(ack.status()) || !body.batchUuid().equals(ack.batchUuid())) {
                return DeliveryResult.retryable("HR answer could not be confirmed");
            }
            return DeliveryResult.acknowledged(Boolean.TRUE.equals(ack.duplicateBatch()));
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            return isRetryable(status)
                    ? DeliveryResult.retryable("HTTP " + status)
                    : DeliveryResult.rejected("HTTP " + status);
        } catch (RestClientException e) {
            return DeliveryResult.retryable("HR unreachable (" + rootCauseName(e) + ")");
        }
    }

    static boolean isRetryable(int status) {
        return status >= 500 || status == 408 || status == 429;
    }

    private static String rootCauseName(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static WireRecord toWire(PayrollRecord r) {
        return new WireRecord(r.attendanceId().toString(), r.employeeId(), r.factoryId(), r.gateId(), r.direction(),
                r.occurredAt().toString());
    }

    record WireRecord(String attendanceId, String employeeId, String factoryId, String gateId, String direction,
                      String occurredAt) {
    }

    record WireBatch(String batchUuid, String period, List<WireRecord> records) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireAck(String batchUuid, String status, Boolean duplicateBatch) {
    }
}
