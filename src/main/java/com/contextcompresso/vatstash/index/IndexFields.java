package com.contextcompresso.vatstash.index;

import com.contextcompresso.vatstash.chunk.CodeChunk;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

/** Lucene field names and document builders. */
public final class IndexFields {

    public static final String DOC_TYPE = "doc_type";
    public static final String TYPE_CHUNK = "chunk";
    public static final String TYPE_FILE = "file";
    public static final String FILE_KEY = "file_key";
    public static final String MANIFEST_KEY = "manifest_key";
    public static final String PATH = "path";
    public static final String PATH_TEXT = "path_text";
    public static final String LANGUAGE = "language";
    public static final String SYMBOL = "symbol";
    public static final String KIND = "kind";
    public static final String START_LINE = "start_line";
    public static final String END_LINE = "end_line";
    public static final String CONTENT = "content";
    public static final String TOKENS = "tokens";
    public static final String SHA256 = "sha256";
    public static final String MTIME = "mtime";
    public static final String SIZE = "size";
    public static final String FILE_TOKENS = "file_tokens";
    public static final String CHUNK_COUNT = "chunk_count";
    public static final String SKIPPED_REASON = "skipped_reason";
    public static final String INDEXED_AT = "indexed_at";

    private IndexFields() {
    }

    public static Document chunkDoc(String rel, TokenizedChunk tc) {
        CodeChunk c = tc.chunk();
        Document d = new Document();
        d.add(new StringField(DOC_TYPE, TYPE_CHUNK, Field.Store.NO));
        d.add(new StringField(FILE_KEY, rel, Field.Store.NO));
        d.add(new StringField(PATH, rel, Field.Store.YES));
        d.add(new TextField(PATH_TEXT, rel, Field.Store.NO));
        d.add(new StringField(LANGUAGE, c.language(), Field.Store.YES));
        if (c.symbol() != null && !c.symbol().isBlank()) {
            d.add(new TextField(SYMBOL, c.symbol(), Field.Store.YES));
        }
        d.add(new StoredField(KIND, c.kind()));
        d.add(new StoredField(START_LINE, c.startLine()));
        d.add(new StoredField(END_LINE, c.endLine()));
        d.add(new TextField(CONTENT, c.content(), Field.Store.YES));
        d.add(new StoredField(TOKENS, tc.tokens()));
        return d;
    }

    public static Document fileDoc(String rel, FileState s) {
        Document d = new Document();
        d.add(new StringField(DOC_TYPE, TYPE_FILE, Field.Store.NO));
        d.add(new StringField(FILE_KEY, rel, Field.Store.YES));
        d.add(new StringField(MANIFEST_KEY, rel, Field.Store.NO));
        d.add(new StoredField(SHA256, s.sha256()));
        d.add(new StoredField(MTIME, s.mtime()));
        d.add(new StoredField(SIZE, s.size()));
        d.add(new StoredField(FILE_TOKENS, s.fileTokens()));
        d.add(new StoredField(CHUNK_COUNT, s.chunkCount()));
        if (s.skippedReason() != null) {
            d.add(new StoredField(SKIPPED_REASON, s.skippedReason()));
        }
        d.add(new StoredField(INDEXED_AT, s.indexedAt()));
        return d;
    }

    public static FileState fileState(Document d) {
        return new FileState(
                d.get(SHA256),
                longOf(d, MTIME),
                longOf(d, SIZE),
                (int) longOf(d, FILE_TOKENS),
                (int) longOf(d, CHUNK_COUNT),
                d.get(SKIPPED_REASON),
                longOf(d, INDEXED_AT));
    }

    private static long longOf(Document d, String field) {
        Number n = d.getField(field) == null ? null : d.getField(field).numericValue();
        return n == null ? 0L : n.longValue();
    }
}
