package com.contextcompresso.vatstash.workspace;

import java.nio.file.Path;

public record WorkspaceRecord(String workspaceId, Path root, WorkspaceSettings settings, long registeredAt,
                              long lastSeenAt) {
}
