package vn.edu.hcmut.tmc.reporting.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The factories' civil calendar (D10 / B24). Every "day" and "month" in reports and in the HR export is a day or month
 * in this zone, never in UTC: a scan at 06:30 on 1 November in Vietnam is 23:30 UTC on 31 October and must count for
 * November.
 */
public final class FactoryCalendar {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private FactoryCalendar() {
    }

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static Instant startOf(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    public static Instant endOf(LocalDate date) {
        return startOf(date.plusDays(1));
    }
}
