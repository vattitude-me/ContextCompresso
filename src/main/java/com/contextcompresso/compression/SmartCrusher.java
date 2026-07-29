package com.contextcompresso.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.contextcompresso.config.CompressionProperties;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structural JSON cleanup: prune null/empty fields, collapse whitespace runs in strings,
 * and truncate oversized arrays. Key dedup (last-wins) is naturally handled by Jackson's
 * ObjectNode during tree construction/rebuild, since re-inserting a key overwrites it.
 */
@Component
public class SmartCrusher {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n{3,}");

    private final int maxArrayElements;

    public SmartCrusher(CompressionProperties properties) {
        this.maxArrayElements = properties.maxArrayElements() <= 0 ? 20 : properties.maxArrayElements();
    }

    public JsonNode crush(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            return crushObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return crushArray((ArrayNode) node);
        }
        if (node.isTextual()) {
            return new TextNode(collapseWhitespace(node.asText()));
        }
        return node;
    }

    private JsonNode crushObject(ObjectNode object) {
        ObjectNode rebuilt = object.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode value = entry.getValue();
            if (isNullOrEmpty(value)) {
                continue;
            }
            // re-insertion on a rebuilt map naturally applies last-wins semantics
            rebuilt.set(entry.getKey(), crush(value));
        }
        return rebuilt;
    }

    private JsonNode crushArray(ArrayNode array) {
        ArrayNode rebuilt = array.arrayNode();
        int n = array.size();
        if (n <= maxArrayElements) {
            for (JsonNode element : array) {
                rebuilt.add(crush(element));
            }
            return rebuilt;
        }

        int headCount = (int) Math.ceil(n / 2.0);
        int tailCount = n / 2;
        // cap combined head+tail at maxArrayElements while preserving the head/tail split ratio
        if (headCount + tailCount > maxArrayElements) {
            headCount = (int) Math.ceil(maxArrayElements / 2.0);
            tailCount = maxArrayElements / 2;
        }

        for (int i = 0; i < headCount; i++) {
            rebuilt.add(crush(array.get(i)));
        }
        rebuilt.add(new TextNode("[...truncated " + (n - headCount - tailCount) + " elements...]"));
        for (int i = n - tailCount; i < n; i++) {
            rebuilt.add(crush(array.get(i)));
        }
        return rebuilt;
    }

    private boolean isNullOrEmpty(JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (value.isTextual() && value.asText().isEmpty()) {
            return true;
        }
        if (value.isObject() && value.isEmpty()) {
            return true;
        }
        if (value.isArray() && value.isEmpty()) {
            return true;
        }
        return false;
    }

    private String collapseWhitespace(String text) {
        String collapsed = WHITESPACE_RUN.matcher(text).replaceAll(" ");
        return BLANK_LINE_RUN.matcher(collapsed).replaceAll("\n\n");
    }
}
