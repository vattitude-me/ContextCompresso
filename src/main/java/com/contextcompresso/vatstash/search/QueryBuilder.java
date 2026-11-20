package com.contextcompresso.vatstash.search;

import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.chunk.LanguageDetector;
import com.contextcompresso.vatstash.index.CodeAnalyzer;
import com.contextcompresso.vatstash.index.IndexFields;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a free-text query into a BM25 query. Terms are matched against the symbol (boost 3),
 * the path (boost 2) and the content (boost 1); at least one term must match.
 */
@Component
public class QueryBuilder {

    static final float SYMBOL_BOOST = 3f;
    static final float PATH_BOOST = 2f;
    static final int MAX_TERMS = 64;

    private final Analyzer analyzer = new CodeAnalyzer();

    public ParsedQuery parse(String raw, String pathPrefix, List<String> languages) {
        if (raw == null || raw.isBlank()) {
            throw VatStashException.invalidRequest("query must not be empty");
        }
        List<String> terms = analyze(raw);
        if (terms.isEmpty()) {
            throw VatStashException.invalidRequest(
                    "query has no searchable terms; use identifiers or words from the code");
        }
        BooleanQuery.Builder match = new BooleanQuery.Builder();
        for (String t : terms) {
            match.add(new BoostQuery(new TermQuery(new Term(IndexFields.SYMBOL, t)), SYMBOL_BOOST),
                    BooleanClause.Occur.SHOULD);
            match.add(new BoostQuery(new TermQuery(new Term(IndexFields.PATH_TEXT, t)), PATH_BOOST),
                    BooleanClause.Occur.SHOULD);
            match.add(new TermQuery(new Term(IndexFields.CONTENT, t)), BooleanClause.Occur.SHOULD);
        }
        match.setMinimumNumberShouldMatch(1);

        BooleanQuery.Builder all = new BooleanQuery.Builder();
        all.add(match.build(), BooleanClause.Occur.MUST);
        all.add(new TermQuery(new Term(IndexFields.DOC_TYPE, IndexFields.TYPE_CHUNK)), BooleanClause.Occur.FILTER);
        String prefix = normalizePrefix(pathPrefix);
        if (prefix != null) {
            all.add(new PrefixQuery(new Term(IndexFields.PATH, prefix)), BooleanClause.Occur.FILTER);
        }
        Query langFilter = languageFilter(languages);
        if (langFilter != null) {
            all.add(langFilter, BooleanClause.Occur.FILTER);
        }
        return new ParsedQuery(raw, terms, all.build());
    }

    private List<String> analyze(String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream(IndexFields.CONTENT, text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken() && terms.size() < MAX_TERMS) {
                terms.add(attr.toString());
            }
            ts.end();
        } catch (IOException e) {
            throw VatStashException.invalidRequest("query could not be analysed: " + e.getMessage());
        }
        return new ArrayList<>(terms);
    }

    static String normalizePrefix(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return null;
        }
        String p = pathPrefix.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() ? null : p;
    }

    private static Query languageFilter(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            return null;
        }
        List<BytesRef> refs = new ArrayList<>();
        for (String l : languages) {
            if (l != null && !l.isBlank()) {
                refs.add(new BytesRef(LanguageDetector.normalizeLanguage(l.trim().toLowerCase())));
            }
        }
        return refs.isEmpty() ? null : new TermInSetQuery(IndexFields.LANGUAGE, refs);
    }
}
