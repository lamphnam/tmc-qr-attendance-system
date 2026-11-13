package vn.edu.hcmut.tmc.kiosk.domain.model;

import java.util.Optional;

/** Metadata and employee observed together; absent metadata means the cache has never been initialized. */
public record WhitelistLookup(Optional<WhitelistMetadata> metadata, Optional<WhitelistEmployee> employee) {

    public WhitelistLookup {
        if (metadata == null || employee == null) {
            throw new IllegalArgumentException("metadata and employee optionals must not be null");
        }
        if (employee.isPresent() && (metadata.isEmpty() || metadata.orElseThrow().employeeCount() == 0)) {
            throw new IllegalArgumentException("an employee requires initialized, non-empty snapshot metadata");
        }
    }

    public static WhitelistLookup uninitialized() {
        return new WhitelistLookup(Optional.empty(), Optional.empty());
    }

    public static WhitelistLookup notFound(WhitelistMetadata metadata) {
        return new WhitelistLookup(Optional.of(requireMetadata(metadata)), Optional.empty());
    }

    public static WhitelistLookup found(WhitelistMetadata metadata, WhitelistEmployee employee) {
        if (employee == null) {
            throw new IllegalArgumentException("employee must not be null");
        }
        return new WhitelistLookup(Optional.of(requireMetadata(metadata)), Optional.of(employee));
    }

    public boolean isInitialized() {
        return metadata.isPresent();
    }

    private static WhitelistMetadata requireMetadata(WhitelistMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        return metadata;
    }
}
