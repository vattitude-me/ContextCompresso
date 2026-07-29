package com.contextcompresso.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Ensures the SQLite db file's parent directory exists before Spring's JDBC datasource
 * initialization (and schema.sql init) runs, and switches on WAL mode for concurrent reads.
 */
@Component
public class SqliteConfig {

    private static final Logger log = LoggerFactory.getLogger(SqliteConfig.class);

    private final String dbPath;
    private final JdbcTemplate jdbcTemplate;

    public SqliteConfig(@Value("${contextcompresso.ccr.db-path}") String dbPath, JdbcTemplate jdbcTemplate) {
        this.dbPath = dbPath;
        this.jdbcTemplate = jdbcTemplate;
        ensureParentDirectory(dbPath);
    }

    private static void ensureParentDirectory(String path) {
        File dbFile = new File(path);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            boolean created = parent.mkdirs();
            if (created) {
                log.info("Created SQLite data directory: {}", parent.getAbsolutePath());
            }
        }
    }

    @PostConstruct
    public void enableWalMode() {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("PRAGMA synchronous=NORMAL");
    }
}
