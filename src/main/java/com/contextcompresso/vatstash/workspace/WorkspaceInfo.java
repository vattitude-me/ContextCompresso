package com.contextcompresso.vatstash.workspace;

import com.contextcompresso.vatstash.index.IndexStatus;

/** Wire shape of a registered workspace: {@code {workspaceId, root, status}}. */
public record WorkspaceInfo(String workspaceId, String root, IndexStatus status) {
}
