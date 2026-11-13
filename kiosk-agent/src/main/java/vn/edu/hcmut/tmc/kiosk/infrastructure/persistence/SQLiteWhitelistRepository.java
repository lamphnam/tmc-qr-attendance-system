package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.stereotype.Repository;

import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException;
import vn.edu.hcmut.tmc.kiosk.domain.exception.WhitelistCacheException.Reason;
import vn.edu.hcmut.tmc.kiosk.domain.model.KioskIdentity;
import vn.edu.hcmut.tmc.kiosk.domain.model.QrStatus;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistEmployee;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistLookup;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistMetadata;
import vn.edu.hcmut.tmc.kiosk.domain.model.WhitelistSnapshot;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistReader;
import vn.edu.hcmut.tmc.kiosk.domain.port.IWhitelistSnapshotStore;

/**
 * Uses the existing kiosk DataSource and schema. D08 is open: only absent secret material can be persisted/read.
 * Supplied material or an existing non-NULL secret BLOB fails closed with UNAVAILABLE; no codec is fabricated.
 * Full snapshot validation on reads detects orphaned/mixed/corrupt rows, at the cost of scanning the whitelist.
 */
@Repository
public class SQLiteWhitelistRepository implements IWhitelistReader, IWhitelistSnapshotStore {

    private static final String KIOSK_ID = "whitelist.kioskId";
    private static final String FACTORY_ID = "whitelist.factoryId";
    private static final String VERSION = "whitelist.version";
    private static final String CACHED_AT = "whitelist.cachedAt";
    private static final String EMPLOYEE_COUNT = "whitelist.employeeCount";
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private static final String SELECT_METADATA = """
            SELECT key, value FROM kiosk_metadata
             WHERE key IN ('whitelist.kioskId', 'whitelist.factoryId', 'whitelist.version',
                           'whitelist.cachedAt', 'whitelist.employeeCount')
            """;
    private static final String SELECT_EMPLOYEES = """
            SELECT employee_id, qr_status, secret_version, cache_version, cached_at,
                   shared_secret_enc IS NOT NULL AS has_secret
              FROM whitelist_cache
            """;
    private static final String INSERT_EMPLOYEE = """
            INSERT INTO whitelist_cache
                (employee_id, qr_status, secret_version, shared_secret_enc, cache_version, cached_at)
            VALUES (?, ?, ?, NULL, ?, ?)
            """;
    private static final String UPSERT_METADATA = """
            INSERT INTO kiosk_metadata (key, value) VALUES (?, ?)
            ON CONFLICT (key) DO UPDATE SET value = excluded.value
            """;

    private final DataSource dataSource;
    private final String kioskId;
    private final String factoryId;

    public SQLiteWhitelistRepository(DataSource dataSource, KioskIdentity identity) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(identity, "identity");
        this.kioskId = requireId(identity.kioskId(), "kioskId");
        this.factoryId = requireId(identity.factoryId(), "factoryId");
    }

    @Override
    public Optional<WhitelistMetadata> inspect() {
        return inTransaction(false, connection -> readSnapshot(connection).map(WhitelistSnapshot::metadata));
    }

    @Override
    public WhitelistLookup lookup(String employeeId) {
        String id = requireId(employeeId, "employeeId");
        return inTransaction(false, connection -> {
            Optional<WhitelistSnapshot> stored = readSnapshot(connection);
            if (stored.isEmpty()) {
                return WhitelistLookup.uninitialized();
            }
            WhitelistSnapshot snapshot = stored.orElseThrow();
            return snapshot.employees().stream().filter(employee -> employee.employeeId().equals(id)).findFirst()
                    .map(employee -> WhitelistLookup.found(snapshot.metadata(), employee))
                    .orElseGet(() -> WhitelistLookup.notFound(snapshot.metadata()));
        });
    }

    @Override
    public boolean replaceIfNewer(WhitelistSnapshot snapshot) {
        if (snapshot == null || !matchesScope(snapshot.metadata())) {
            throw new IllegalArgumentException("snapshot must match the configured kiosk and factory scope");
        }
        // Model constructors already validate immutable snapshot contents. Compare versions only after the lock.
        return inTransaction(true, connection -> {
            Optional<WhitelistSnapshot> current = readSnapshot(connection);
            if (current.isPresent() && current.orElseThrow().metadata().version() >= snapshot.metadata().version()) {
                return false;
            }
            for (WhitelistEmployee employee : snapshot.employees()) {
                if (employee.secretMaterial() != null) {
                    throw new WhitelistCacheException(Reason.UNAVAILABLE);
                }
            }
            execute(connection, "DELETE FROM whitelist_cache");
            insertEmployees(connection, snapshot);
            writeMetadata(connection, snapshot.metadata());
            return true;
        });
    }

    private Optional<WhitelistSnapshot> readSnapshot(Connection connection) throws SQLException {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(SELECT_METADATA)) {
                while (rows.next()) {
                    values.put(rows.getString("key"), rows.getString("value"));
                }
            }
            WhitelistMetadata metadata = null;
            if (!values.isEmpty()) {
                if (values.size() != 5 || values.containsValue(null)) {
                    throw new WhitelistCacheException(Reason.INVALID_CACHE);
                }
                Instant cachedAt = parseInstant(values.get(CACHED_AT));
                metadata = new WhitelistMetadata(values.get(KIOSK_ID), values.get(FACTORY_ID),
                        Long.parseLong(values.get(VERSION)), cachedAt, Integer.parseInt(values.get(EMPLOYEE_COUNT)));
                if (!matchesScope(metadata) || !cachedAt.equals(metadata.cachedAt())) {
                    throw new WhitelistCacheException(Reason.INVALID_CACHE);
                }
            }

            List<WhitelistEmployee> employees = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(SELECT_EMPLOYEES)) {
                while (rows.next()) {
                    if (metadata == null || Long.parseLong(rows.getString("cache_version")) != metadata.version()
                            || !parseInstant(rows.getString("cached_at")).equals(metadata.cachedAt())) {
                        throw new WhitelistCacheException(Reason.INVALID_CACHE);
                    }
                    if (rows.getBoolean("has_secret")) {
                        throw new WhitelistCacheException(Reason.UNAVAILABLE);
                    }
                    String status = rows.getString("qr_status");
                    employees.add(new WhitelistEmployee(rows.getString("employee_id"),
                            status == null ? null : QrStatus.valueOf(status),
                            Integer.parseInt(rows.getString("secret_version")), null));
                }
            }
            return metadata == null ? Optional.empty() : Optional.of(new WhitelistSnapshot(metadata, employees));
        } catch (IllegalArgumentException | DateTimeException invalidStoredData) {
            // Driver/parser messages and causes may contain stored data; expose only the fixed domain reason.
            throw new WhitelistCacheException(Reason.INVALID_CACHE);
        }
    }

    private static void insertEmployees(Connection connection, WhitelistSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_EMPLOYEE)) {
            for (WhitelistEmployee employee : snapshot.employees()) {
                statement.setString(1, employee.employeeId());
                statement.setString(2, employee.qrStatus().name());
                statement.setInt(3, employee.secretVersion());
                statement.setLong(4, snapshot.metadata().version());
                statement.setString(5, TIMESTAMP.format(snapshot.metadata().cachedAt()));
                statement.addBatch();
            }
            if (!snapshot.employees().isEmpty()) {
                statement.executeBatch();
            }
        }
    }

    private static void writeMetadata(Connection connection, WhitelistMetadata metadata) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(KIOSK_ID, metadata.kioskId());
        values.put(FACTORY_ID, metadata.factoryId());
        values.put(VERSION, Long.toString(metadata.version()));
        values.put(CACHED_AT, TIMESTAMP.format(metadata.cachedAt()));
        values.put(EMPLOYEE_COUNT, Integer.toString(metadata.employeeCount()));
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_METADATA)) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.executeUpdate();
            }
        }
    }

    private <T> T inTransaction(boolean write, SqlWork<T> work) {
        // A fresh connection avoids joining a caller's transaction and returning before its eventual commit.
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, write ? "BEGIN IMMEDIATE" : "BEGIN");
            try {
                T result = work.run(connection);
                execute(connection, "COMMIT");
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    execute(connection, "ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    // Closing releases the connection; do not attach potentially sensitive driver diagnostics.
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new WhitelistCacheException(Reason.UNAVAILABLE);
        }
    }

    private boolean matchesScope(WhitelistMetadata metadata) {
        return kioskId.equals(metadata.kioskId()) && factoryId.equals(metadata.factoryId());
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            throw new IllegalArgumentException("cachedAt must not be null");
        }
        return Instant.parse(value);
    }

    private static String requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
