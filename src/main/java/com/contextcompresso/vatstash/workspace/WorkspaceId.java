package com.contextcompresso.vatstash.workspace;

import com.contextcompresso.util.HashUtil;

import java.io.IOException;
import java.nio.file.Path;

/** First 16 hex characters of sha256 of the root's real path. */
public final class WorkspaceId {

    private WorkspaceId() {
    }

    public static String of(Path root) {
        Path real;
        try {
            real = root.toRealPath();
        } catch (IOException e) {
            real = root.toAbsolutePath().normalize();
        }
        return HashUtil.sha256Hex(real.toString()).substring(0, 16);
    }
}
