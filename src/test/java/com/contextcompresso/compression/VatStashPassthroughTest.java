package com.contextcompresso.compression;

import com.contextcompresso.ccr.CcrStore;
import com.contextcompresso.config.CompressionProperties;
import com.contextcompresso.model.CompressedRequest;
import com.contextcompresso.provider.Provider;
import com.contextcompresso.provider.ProviderConfig;
import com.contextcompresso.tokenizer.AnthropicEstimator;
import com.contextcompresso.tokenizer.CharRatioEstimator;
import com.contextcompresso.tokenizer.JtokkitEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.JDBC;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VatStashPassthroughTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private CompressionPipeline pipeline;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(JDBC.class);
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("ccr.db").toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        CompressionProperties props = new CompressionProperties(true, 20, 8000, true, 5, 3, 200, 2000, true, 2000);
        pipeline = new CompressionPipeline(new CacheAligner(), new SmartCrusher(props), new CodeCompressor(),
                new TextTruncator(), new ToolResultCompactor(), new CcrStore(new JdbcTemplate(ds)), props,
                new SimpleMeterRegistry(), mapper, new JtokkitEstimator(), new AnthropicEstimator(),
                new CharRatioEstimator());
    }

    private static ProviderConfig config(Provider provider, String style, String estimator) {
        return new ProviderConfig(provider, "http://localhost", style, "api-key", 200000, estimator,
                List.of(), true, false, true, false, false);
    }

    private static String vatstashText() {
        StringBuilder sb = new StringBuilder("[vatstash v1] 12 results, 3 files\n");
        for (int i = 0; i < 400; i++) {
            sb.append("src/main/java/demo/File").append(i).append(".java:").append(i)
                    .append(": // comment line ").append(i).append(" with some filler words\n");
        }
        return sb.toString();
    }

    @Test
    void markedToolResultIsNotCompacted() throws Exception {
        String text = vatstashText();
        ObjectNode block = mapper.createObjectNode().put("type", "tool_result").put("tool_use_id", "t1")
                .put("content", text);
        ObjectNode root = mapper.createObjectNode().put("model", "claude-opus-5");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").putArray("content").add(block);

        CompressedRequest out = pipeline.compress(mapper.writeValueAsString(root),
                config(Provider.CLAUDE, "anthropic", "anthropic"), "req-marked");

        assertThat(out.body().at("/messages/0/content/0/content").asText()).isEqualTo(text);

        // same, with the array form of tool_result content
        ObjectNode block2 = mapper.createObjectNode().put("type", "tool_result").put("tool_use_id", "t2");
        block2.putArray("content").addObject().put("type", "text").put("text", text);
        ObjectNode root2 = mapper.createObjectNode().put("model", "claude-opus-5");
        root2.putArray("messages").addObject().put("role", "user").putArray("content").add(block2);
        CompressedRequest out2 = pipeline.compress(mapper.writeValueAsString(root2),
                config(Provider.CLAUDE, "anthropic", "anthropic"), "req-marked2");
        assertThat(out2.body().at("/messages/0/content/0/content/0/text").asText()).isEqualTo(text);
    }

    @Test
    void markedOpenAiToolMessageIsNotTruncatedOrCommentStripped() throws Exception {
        String text = vatstashText();
        ObjectNode root = mapper.createObjectNode().put("model", "gpt-4o");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "user").put("content", "find it");
        messages.addObject().put("role", "tool").put("tool_call_id", "c1").put("content", text);

        CompressedRequest out = pipeline.compress(mapper.writeValueAsString(root),
                config(Provider.DEFAULT, "openai", "char-ratio"), "req-openai");

        JsonNode msgs = out.body().get("messages");
        assertThat(msgs.size()).isEqualTo(2);
        assertThat(msgs.get(1).get("content").asText()).isEqualTo(text);
        assertThat(msgs.get(1).get("content").asText()).contains("// comment line 399");
    }

    @Test
    void unmarkedToolResultStillCompacted() throws Exception {
        String text = vatstashText().replace("[vatstash v1]", "plain output");
        ObjectNode block = mapper.createObjectNode().put("type", "tool_result").put("tool_use_id", "t3")
                .put("content", text);
        ObjectNode root = mapper.createObjectNode().put("model", "claude-opus-5");
        root.putArray("messages").addObject().put("role", "user").putArray("content").add(block);

        CompressedRequest out = pipeline.compress(mapper.writeValueAsString(root),
                config(Provider.CLAUDE, "anthropic", "anthropic"), "req-unmarked");

        String compacted = out.body().at("/messages/0/content/0/content").asText();
        assertThat(compacted.length()).isLessThan(text.length());
        assertThat(compacted).contains("lines omitted");
    }
}
