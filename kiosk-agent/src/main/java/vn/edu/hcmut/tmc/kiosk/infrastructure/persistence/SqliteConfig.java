package vn.edu.hcmut.tmc.kiosk.infrastructure.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import vn.edu.hcmut.tmc.kiosk.config.KioskProperties;

/**
 * The kiosk's one durable store. Every connection is opened with WAL + {@code synchronous=FULL} (team plan, docs/DATA-MODEL.md);
 * a commit therefore returns only after the WAL is flushed. That protects against a process crash or restart - it does
 * NOT protect against physical disk failure, and the guarantee still depends on the underlying filesystem.
 */
@Configuration
public class SqliteConfig {

    @Bean
    DataSource dataSource(KioskProperties properties) {
        return createDataSource(Path.of(properties.sqlite().path()));
    }

    /** Public and static so tests build the exact production configuration against a temp file. */
    public static DataSource createDataSource(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create directory for the kiosk SQLite file", e);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);

        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + absolute);

        // Idempotent CREATE ... IF NOT EXISTS - never drops or alters existing queue data.
        new ResourceDatabasePopulator(new ClassPathResource("sqlite/schema.sql")).execute(dataSource);
        return dataSource;
    }
}
