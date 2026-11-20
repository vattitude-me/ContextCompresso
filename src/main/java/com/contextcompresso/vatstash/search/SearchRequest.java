package com.contextcompresso.vatstash.search;

import java.util.List;

/** Body of {@code POST /vatstash/search}. Either {@code workspaceIds} or {@code root} is required. */
public record SearchRequest(
        String query,
        List<String> workspaceIds,
        String root,
        Integer maxResults,
        Integer maxTokens,
        String pathPrefix,
        List<String> languages,
        String client
) {
}
