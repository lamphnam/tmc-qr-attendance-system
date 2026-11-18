package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.model.Direction;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.GateCommandStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.RejectedScan;
import vn.edu.hcmut.tmc.kiosk.domain.model.RejectedScan.Reason;
import vn.edu.hcmut.tmc.kiosk.domain.model.ScanResult;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistLookup;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;
import vn.edu.hcmut.tmc.kiosk.domain.port.ITurnstileAdapter;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistReader;
import vn.edu.hcmut.tmc.kiosk.domain.service.TotpValidatorDomainService;

@Service
public class ProcessQrScanUseCase {
    public record Outcome(ScanResult result, UUID attendanceId, GateCommandStatus gateCommand,
                          long processingTimeMs, Reason reason) {
    }

    private final IWhitelistReader whitelist;
    private final TotpValidatorDomainService validator;
    private final IAttendanceLocalRepository repository;
    private final ITurnstileAdapter turnstile;
    private final KioskIdentity identity;
    private final Clock clock;

    public ProcessQrScanUseCase(IWhitelistReader whitelist, TotpValidatorDomainService validator,
                                IAttendanceLocalRepository repository, ITurnstileAdapter turnstile,
                                KioskIdentity identity, Clock clock) {
        this.whitelist = whitelist;
        this.validator = validator;
        this.repository = repository;
        this.turnstile = turnstile;
        this.identity = identity;
        this.clock = clock;
    }

    public Outcome execute(String gateId, Direction direction, String employeeId, String code, int version) {
        long started = System.nanoTime();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        WhitelistLookup lookup = whitelist.lookup(employeeId);
        if (lookup.metadata().isEmpty()) {
            throw new WhitelistCacheException(WhitelistCacheException.Reason.UNAVAILABLE);
        }
        WhitelistEmployee employee = lookup.employee().orElse(null);
        if (employee == null) {
            return rejected(new RejectedScan(id, employeeId, gateId, direction, now, Reason.UNKNOWN_EMPLOYEE), started);
        }
        if (employee.qrStatus() == QrStatus.REVOKED) {
            return rejected(new RejectedScan(id, employeeId, gateId, direction, now, Reason.REVOKED), started);
        }
        if (employee.secretVersion() != version) {
            return rejected(new RejectedScan(id, employeeId, gateId, direction, now, Reason.INVALID_TOTP), started);
        }
        OptionalLong matchedStep = validator.match(employee.secretMaterial(), code);
        if (matchedStep.isEmpty()) {
            return rejected(new RejectedScan(id, employeeId, gateId, direction, now, Reason.INVALID_TOTP), started);
        }

        LocalAttendanceEvent pending = LocalAttendanceEvent.newPending(id, employeeId, identity.factoryId(),
                gateId, direction, now, ScanResult.ALLOWED, false, now);
        if (!repository.insertPendingWithToken(pending, matchedStep.getAsLong())) {
            return rejected(new RejectedScan(id, employeeId, gateId, direction, now, Reason.REPLAYED), started);
        }

        GateCommandResult command = turnstile.openGate(gateId);
        repository.updateGateCommandStatus(id, command.status(), command.detail());
        return new Outcome(ScanResult.ALLOWED, id, command.status(), elapsedMs(started), null);
    }

    private Outcome rejected(RejectedScan scan, long started) {
        repository.insertRejectedScan(scan);
        return new Outcome(ScanResult.REJECTED, null, GateCommandStatus.NOT_ATTEMPTED, elapsedMs(started), scan.reason());
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
