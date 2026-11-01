package vn.edu.hcmut.tmc.kiosk.infrastructure.turnstile;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.port.ITurnstileAdapter;

/**
 * Local-network turnstile adapter. In this bootstrap the "turnstile" is the mock HTTP service, so a SENT result means
 * "the mock accepted the command" and nothing more. A real device protocol (e.g. a ZKTeco adapter) is a separate
 * implementation of {@link ITurnstileAdapter} owned by Khanh; it should return UNSUPPORTED rather than pretend.
 *
 * <p>Never throws, and never touches central: it must work while kiosk -> central sync is off.
 */
@Component
public class EthernetTurnstileAdapter implements ITurnstileAdapter {

    private final RestClient client;
    private final KioskIdentity identity;

    public EthernetTurnstileAdapter(@Qualifier("turnstileRestClient") RestClient client, KioskIdentity identity) {
        this.client = client;
        this.identity = identity;
    }

    @Override
    public GateCommandResult openGate(String gateId) {
        try {
            ResponseEntity<Void> response = client.post()
                    .uri("/gates/{gateId}/open", gateId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("kioskId", identity.kioskId()))
                    .retrieve()
                    .toBodilessEntity();
            return new GateCommandResult(GateCommandStatus.SENT,
                    "turnstile endpoint accepted the command (HTTP " + response.getStatusCode().value()
                            + "); physical opening is NOT confirmed");
        } catch (RestClientResponseException e) {
            return new GateCommandResult(GateCommandStatus.FAILED,
                    "turnstile endpoint answered HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            return new GateCommandResult(GateCommandStatus.FAILED,
                    "turnstile endpoint unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }
}
