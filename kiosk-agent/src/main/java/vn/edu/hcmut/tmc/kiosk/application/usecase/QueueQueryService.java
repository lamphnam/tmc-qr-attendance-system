package vn.edu.hcmut.tmc.kiosk.application.usecase;

import java.util.List;

import org.springframework.stereotype.Service;

import vn.edu.hcmut.tmc.kiosk.domain.model.LocalAttendanceEvent;
import vn.edu.hcmut.tmc.kiosk.domain.model.QueueCounts;
import vn.edu.hcmut.tmc.kiosk.domain.port.IAttendanceLocalRepository;

/** Read side of the local queue, so controllers never touch the repository port directly. */
@Service
public class QueueQueryService {

    public record QueueSnapshot(QueueCounts counts, List<LocalAttendanceEvent> recent) {
    }

    private final IAttendanceLocalRepository repository;

    public QueueQueryService(IAttendanceLocalRepository repository) {
        this.repository = repository;
    }

    public QueueSnapshot snapshot(int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        return new QueueSnapshot(repository.countByStatus(), repository.findRecent(bounded));
    }
}
