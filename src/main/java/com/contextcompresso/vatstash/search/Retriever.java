package com.contextcompresso.vatstash.search;

import org.apache.lucene.search.IndexSearcher;

import java.io.IOException;
import java.util.List;

/** The retrieval seam. Dense retrieval and rank fusion would add implementations next to the BM25 one. */
public interface Retriever {

    List<ScoredChunk> retrieve(IndexSearcher searcher, ParsedQuery query, int k) throws IOException;
}
