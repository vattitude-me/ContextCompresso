package com.contextcompresso.vatstash.workspace;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.index.WorkspaceIndex;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.store.LockObtainFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Registered workspaces (persisted in SQLite so they survive proxy restarts) and the open
 * Lucene indexes (opened lazily, least recently used closed beyond {@code maxOpenWorkspaces}).
 */
@Component
public class WorkspaceRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRegistry.class);
    private static final int LOCK_RETRIES = 3;
    private static final long LOCK_RETRY_DELAY_MS = 500;

    private final JdbcTemplate jdbc;
    private final VatStashProperties props;
    private final ObjectMapper mapper;
    private final Path baseDir;
    private final Map<String, OpenIndex> open = new ConcurrentHashMap<>();

    private final RowMapper<WorkspaceRecord> rowMapper;

    public WorkspaceRegistry(JdbcTemplate jdbc, VatStashProperties props, ObjectMapper mapper,
                             @Value("${contextcompresso.ccr.db-path:./data/ccr.db}") String dbPath) {
        this.jdbc = jdbc;
        this.props = props;
        this.mapper = mapper;
        this.baseDir = resolveBaseDir(props, dbPath);
        this.rowMapper = (rs, n) -> new WorkspaceRecord(
                rs.getString("workspace_id"),
                Paths.get(rs.getString("root")),
                parseSettings(rs.getString("settings_json")),
                rs.getLong("registered_at"),
                rs.getLong("last_seen_at"));
    }

    private static Path resolveBaseDir(VatStashProperties props, String dbPath) {
        if (props.indexDir() != null && !props.indexDir().isBlank()) {
            return Paths.get(props.indexDir()).toAbsolutePath().normalize();
        }
        Path parent = Paths.get(dbPath == null || dbPath.isBlank() ? "./data/ccr.db" : dbPath)
                .toAbsolutePath().normalize().getParent();
        return (parent == null ? Paths.get(".").toAbsolutePath().normalize() : parent).resolve("vatstash");
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path workspaceDir(String id) {
        return baseDir.resolve(id);
    }

    // ---- root validation ----

    /** Returns the real path of {@code root} or throws invalid_root. */
    public static Path validateRoot(String root) {
        if (root == null || root.isBlank()) {
            throw VatStashException.invalidRoot("root is required");
        }
        Path p;
        try {
            p = Paths.get(root);
        } catch (InvalidPathException e) {
            throw VatStashException.invalidRoot("root is not a valid path: " + root);
        }
        if (!p.isAbsolute()) {
            throw VatStashException.invalidRoot("root must be an absolute path: " + root);
        }
        if (!Files.isDirectory(p)) {
            throw VatStashException.invalidRoot("root is not an existing directory: " + root);
        }
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw VatStashException.invalidRoot("root cannot be resolved: " + root);
        }
        if (real.getParent() == null) {
            throw VatStashException.invalidRoot("root must not be the filesystem root");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            try {
                if (real.equals(Paths.get(home).toRealPath())) {
                    throw VatStashException.invalidRoot("root must not be the user's home directory");
                }
            } catch (IOException | InvalidPathException ignored) {
                // home unresolvable: nothing to compare against
            }
        }
        return real;
    }

    // ---- persistence ----

    public WorkspaceRecord register(String root, WorkspaceSettings settings) {
        Path real = validateRoot(root);
        String id = WorkspaceId.of(real);
        long now = System.currentTimeMillis();
        WorkspaceSettings effective = settings == null ? WorkspaceSettings.empty() : settings;
        jdbc.update("INSERT INTO vatstash_workspaces (workspace_id, root, settings_json, registered_at, last_seen_at) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(workspace_id) DO UPDATE SET "
                        + "root = excluded.root, settings_json = excluded.settings_json, "
                        + "last_seen_at = excluded.last_seen_at",
                id, real.toString(), writeSettings(effective), now, now);
        return find(id).orElseThrow();
    }

    public Optional<WorkspaceRecord> find(String id) {
        List<WorkspaceRecord> rows = jdbc.query(
                "SELECT * FROM vatstash_workspaces WHERE workspace_id = ?", rowMapper, id);
        return rows.stream().findFirst();
    }

    public List<WorkspaceRecord> list() {
        return jdbc.query("SELECT * FROM vatstash_workspaces ORDER BY registered_at ASC", rowMapper);
    }

    /** The registered workspace whose root is the longest prefix of {@code path}. */
    public Optional<WorkspaceRecord> findByPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path p;
        try {
            p = Paths.get(path);
            if (!p.isAbsolute()) {
                return Optional.empty();
            }
            p = Files.exists(p) ? p.toRealPath() : p.normalize();
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
        WorkspaceRecord best = null;
        for (WorkspaceRecord r : list()) {
            if (p.startsWith(r.root())
                    && (best == null || r.root().getNameCount() > best.root().getNameCount())) {
                best = r;
            }
        }
        return Optional.ofNullable(best);
    }

    public void touch(String id) {
        jdbc.update("UPDATE vatstash_workspaces SET last_seen_at = ? WHERE workspace_id = ?",
                System.currentTimeMillis(), id);
    }

    // ---- open indexes ----

    public boolean isOpen(String id) {
        OpenIndex o = open.get(id);
        return o != null && !o.index.isClosed();
    }

    /** Opens the index lazily. Throws workspace_not_found or vatstash_unavailable. */
    public synchronized WorkspaceIndex openIndex(String id) {
        OpenIndex existing = open.get(id);
        if (existing != null && !existing.index.isClosed()) {
            existing.lastUsed = System.nanoTime();
            return existing.index;
        }
        if (find(id).isEmpty()) {
            throw VatStashException.workspaceNotFound("workspace " + id);
        }
        WorkspaceIndex index = openWithRecovery(id);
        open.put(id, new OpenIndex(index));
        evictBeyondLimit(id);
        return index;
    }

    private WorkspaceIndex openWithRecovery(String id) {
        Path dir = workspaceDir(id);
        try {
            return openWithRetry(dir);
        } catch (CorruptIndexException | IndexFormatTooOldException | IndexFormatTooNewException e) {
            log.warn("VatStash index for workspace {} is unreadable ({}); rebuilding from scratch", id,
                    e.getMessage());
            try {
                deleteRecursively(dir);
                return openWithRetry(dir);
            } catch (IOException | RuntimeException e2) {
                throw VatStashException.unavailable("VatStash index could not be rebuilt: " + e2.getMessage(), e2);
            }
        } catch (IOException | RuntimeException e) {
            throw VatStashException.unavailable("VatStash index could not be opened: " + e.getMessage(), e);
        }
    }

    private WorkspaceIndex openWithRetry(Path dir) throws IOException {
        LockObtainFailedException last = null;
        for (int attempt = 0; attempt < LOCK_RETRIES; attempt++) {
            try {
                return WorkspaceIndex.open(dir, props);
            } catch (LockObtainFailedException e) {
                last = e;
                try {
                    Thread.sleep(LOCK_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    private void evictBeyondLimit(String keepId) {
        int max = Math.max(1, props.maxOpenWorkspaces());
        while (open.size() > max) {
            Optional<Map.Entry<String, OpenIndex>> victim = open.entrySet().stream()
                    .filter(e -> !e.getKey().equals(keepId) && !e.getValue().index.isPinned())
                    .min(Comparator.comparingLong(e -> e.getValue().lastUsed));
            if (victim.isEmpty()) {
                return;
            }
            closeIndex(victim.get().getKey());
        }
    }

    public synchronized void closeIndex(String id) {
        OpenIndex o = open.remove(id);
        if (o != null) {
            try {
                o.index.close();
            } catch (IOException | RuntimeException e) {
                log.warn("Closing VatStash index {} failed: {}", id, e.getMessage());
            }
        }
    }

    /** Unregisters a workspace: closes the index, deletes its directory and the registry row. */
    public synchronized void remove(String id) {
        closeIndex(id);
        try {
            deleteRecursively(workspaceDir(id));
        } catch (IOException e) {
            log.warn("Deleting VatStash index directory for {} failed: {}", id, e.getMessage());
        }
        jdbc.update("DELETE FROM vatstash_workspaces WHERE workspace_id = ?", id);
    }

    public synchronized void closeAll() {
        for (String id : new ArrayList<>(open.keySet())) {
            closeIndex(id);
        }
    }

    public List<String> openIds() {
        return new ArrayList<>(open.keySet());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ---- settings json ----

    private String writeSettings(WorkspaceSettings s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (IOException e) {
            return "{}";
        }
    }

    private WorkspaceSettings parseSettings(String json) {
        if (json == null || json.isBlank()) {
            return WorkspaceSettings.empty();
        }
        try {
            return mapper.readValue(json, WorkspaceSettings.class);
        } catch (IOException e) {
            return WorkspaceSettings.empty();
        }
    }

    private static final class OpenIndex {
        final WorkspaceIndex index;
        volatile long lastUsed = System.nanoTime();

        OpenIndex(WorkspaceIndex index) {
            this.index = index;
        }
    }
}
