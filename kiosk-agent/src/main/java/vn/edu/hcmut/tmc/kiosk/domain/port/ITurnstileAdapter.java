package vn.edu.hcmut.tmc.kiosk.domain.port;

import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;

/** Local-network turnstile command port. Implementations never throw: failures come back as FAILED / UNSUPPORTED. */
public interface ITurnstileAdapter {

    GateCommandResult openGate(String gateId);
}
