package com.contextcompresso.vatstash.search;

import com.contextcompresso.vatstash.index.IndexFields;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Lexical retrieval: BM25 over the symbol, path and content fields. */
@Component
public class Bm25Retriever implements Retriever {

    @Override
    public List<ScoredChunk> retrieve(IndexSearcher searcher, ParsedQuery query, int k) throws IOException {
        TopDocs top = searcher.search(query.query(), Math.max(1, k));
        List<ScoredChunk> out = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc sd : top.scoreDocs) {
            Document d = searcher.storedFields().document(sd.doc);
            out.add(new ScoredChunk(
                    sd.doc,
                    sd.score,
                    d.get(IndexFields.PATH),
                    d.get(IndexFields.LANGUAGE),
                    d.get(IndexFields.SYMBOL),
                    d.get(IndexFields.KIND),
                    intOf(d, IndexFields.START_LINE),
                    intOf(d, IndexFields.END_LINE),
                    d.get(IndexFields.CONTENT),
                    intOf(d, IndexFields.TOKENS)));
        }
        return out;
    }

    private static int intOf(Document d, String field) {
        Number n = d.getField(field) == null ? null : d.getField(field).numericValue();
        return n == null ? 0 : n.intValue();
    }
}
