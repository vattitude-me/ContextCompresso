package com.contextcompresso.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.io.File;

/**
 * Creates the SQLite db file's parent directory before any bean (including the
 * DataSource) is created, since Spring's datasource auto-configuration connects
 * eagerly and SQLite refuses to create missing parent directories itself.
 *
 * Registered programmatically in {@code main()} rather than via a
 * META-INF/spring.factories-style resource file: Spring Boot's repackaging
 * hoists this project's own META-INF/* resources to the fat jar's root, which
 * the runtime BOOT-INF classloader never scans, so resource-based registration
 * silently no-ops for classes defined in this jar.
 */
public class CcrDataDirectoryInitializer implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        String dbPath = event.getEnvironment().getProperty("contextcompresso.ccr.db-path");
        if (dbPath == null || dbPath.isBlank()) {
            return;
        }
        File parent = new File(dbPath).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
    }
}
