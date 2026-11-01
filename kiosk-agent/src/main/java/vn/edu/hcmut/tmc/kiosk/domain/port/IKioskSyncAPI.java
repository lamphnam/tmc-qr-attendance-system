package vn.edu.hcmut.tmc.kiosk.domain.port;

import java.util.List;

import vn.edu.hcmut.tmc.kiosk.domain.exception.CentralUnavailableException;
import vn.edu.hcmut.tmc.kiosk.domain.model.EventSyncResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;

/** Kiosk -> central sync port (docs/API-CONTRACTS.md section 5). */
public interface IKioskSyncAPI {

    /**
     * Sends the events with their stable ids and returns one result per acknowledged event.
     *
     * @throws CentralUnavailableException when the request as a whole failed and nothing can be assumed committed
     */
    List<EventSyncResult> syncBatch(String kioskId, List<LocalAttendanceEvent> events);
}
