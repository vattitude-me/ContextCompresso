package com.contextcompresso.vatstash.search;

import org.apache.lucene.search.Query;

import java.util.List;

/** A user query turned into a Lucene query plus the analysed terms it was built from. */
public record ParsedQuery(String raw, List<String> terms, Query query) {
}
