package com.contextcompresso.vatstash.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Per-workspace overrides sent by the client at registration; every field is optional. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceSettings(List<String> excludeGlobs, Integer maxFileBytes, Integer maxFiles) {

    public WorkspaceSettings {
        excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
    }

    public static WorkspaceSettings empty() {
        return new WorkspaceSettings(List.of(), null, null);
    }
}
