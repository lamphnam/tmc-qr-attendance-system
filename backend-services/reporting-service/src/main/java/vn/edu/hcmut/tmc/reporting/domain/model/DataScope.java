package vn.edu.hcmut.tmc.reporting.domain.model;

/**
 * Which central rows count as real data. Rows written through the dev-only {@code /api/dev/attendance/*} fixtures carry
 * {@code dev_fixture = true} (B13) and are excluded unless a developer stack opts in explicitly.
 */
public record DataScope(boolean includeDevFixtures) {

    public static DataScope productionOnly() {
        return new DataScope(false);
    }
}
