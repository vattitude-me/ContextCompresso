package com.contextcompresso.config;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Switches on WAL mode for concurrent reads. The db file's parent directory is
 * created earlier by {@link CcrDataDirectoryInitializer}, before the datasource
 * bean (and thus this component) is constructed.
 */
@Component
public class SqliteConfig {

    private final JdbcTemplate jdbcTemplate;

    public SqliteConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void enableWalMode() {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("PRAGMA synchronous=NORMAL");
    }
}
