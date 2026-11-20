package com.contextcompresso.vatstash.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class JavaParserChunkerTest {

    private final HeuristicChunker heuristic = new HeuristicChunker(15, 120, 10, 6000);
    private final JavaParserChunker chunker = new JavaParserChunker(heuristic, 120);

    private static final String SERVICE = """
            package com.example.pay;

            import java.util.List;

            public class PaymentService {

                private final Gateway gateway;

                /**
                 * Charges the card and retries with backoff.
                 */
                @Transactional
                public Receipt retryCharge(String cardId, long cents) {
                    int attempt = 0;
                    while (attempt < 3) {
                        try {
                            return gateway.charge(cardId, cents);
                        } catch (GatewayException e) {
                            attempt++;
                            sleepBackoff(attempt);
                        }
                    }
                    throw new IllegalStateException("charge failed");
                }

                public PaymentService(Gateway gateway) {
                    this.gateway = gateway;
                    this.gateway.init();
                    this.gateway.warmUp();
                    this.gateway.verify();
                }

                private void sleepBackoff(int attempt) {
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            """;

    private List<CodeChunk> chunk(String src) {
        return chunker.chunk("src/PaymentService.java", "java", src);
    }

    @Test
    void splitsClassIntoTypeOutlineAndMethodChunks() {
        List<CodeChunk> chunks = chunk(SERVICE);

        assertThat(chunks).extracting(CodeChunk::kind).contains("type", "method", "constructor");
        CodeChunk type = chunks.stream().filter(c -> c.kind().equals("type")).findFirst().orElseThrow();
        assertThat(type.symbol()).isEqualTo("PaymentService");
        assertThat(type.content()).contains("class PaymentService").contains("retryCharge");
        assertThat(type.content()).doesNotContain("sleepBackoff(attempt);");
        assertThat(chunks).extracting(CodeChunk::symbol)
                .contains("PaymentService.retryCharge", "PaymentService.sleepBackoff");
    }

    @Test
    void methodChunkIncludesJavadocAndAnnotations() {
        CodeChunk retry = chunk(SERVICE).stream()
                .filter(c -> "PaymentService.retryCharge".equals(c.symbol())).findFirst().orElseThrow();

        assertThat(retry.content()).contains("Charges the card and retries with backoff.");
        assertThat(retry.content()).contains("@Transactional");
        assertThat(retry.content()).contains("throw new IllegalStateException");
        assertThat(retry.startLine()).isEqualTo(9);
        assertThat(retry.endLine()).isEqualTo(24);
    }

    @Test
    void groupsTinyMembersIntoMembersChunk() {
        String src = """
                public class Flags {
                    public boolean a() { return true; }
                    public boolean b() { return false; }
                    public int c() { return 3; }
                    public int d() { return 4; }
                }
                """;
        List<CodeChunk> chunks = chunker.chunk("Flags.java", "java", src);

        assertThat(chunks).extracting(CodeChunk::kind).contains("members");
        CodeChunk members = chunks.stream().filter(c -> c.kind().equals("members")).findFirst().orElseThrow();
        assertThat(members.content()).contains("a()").contains("b()").contains("d()");
    }

    @Test
    void nestedClassSymbolsAreQualified() {
        String body = IntStream.rangeClosed(1, 8).mapToObj(i -> "            int v" + i + " = " + i + ";")
                .collect(Collectors.joining("\n"));
        String src = "public class Outer {\n"
                + "    static class Inner {\n"
                + "        void deep() {\n"
                + body + "\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        List<CodeChunk> chunks = chunker.chunk("Outer.java", "java", src);

        assertThat(chunks).extracting(CodeChunk::symbol).contains("Outer", "Outer.Inner", "Outer.Inner.deep");
    }

    @Test
    void oversizedMethodIsWindowedWithOverlap() {
        HeuristicChunker small = new HeuristicChunker(5, 20, 5, 6000);
        JavaParserChunker windowed = new JavaParserChunker(small, 20);
        String body = IntStream.rangeClosed(1, 70).mapToObj(i -> "        total += " + i + ";")
                .collect(Collectors.joining("\n"));
        String src = "public class Big {\n    void run() {\n        int total = 0;\n" + body + "\n    }\n}\n";

        List<CodeChunk> chunks = windowed.chunk("Big.java", "java", src);
        List<CodeChunk> windows = chunks.stream().filter(c -> c.kind().equals("window")).toList();

        assertThat(windows).hasSizeGreaterThanOrEqualTo(3);
        for (int i = 1; i < windows.size(); i++) {
            assertThat(windows.get(i).startLine()).isLessThanOrEqualTo(windows.get(i - 1).endLine());
        }
        assertThat(windows).allSatisfy(w -> assertThat(w.endLine() - w.startLine() + 1).isLessThanOrEqualTo(20));
        assertThat(windows).allSatisfy(w -> assertThat(w.symbol()).isEqualTo("Big.run"));
    }

    @Test
    void unparseableSourceFallsBackToHeuristic() {
        String src = "public class Broken {\n    void a( {{{\n    int x = ;\n}\n";
        List<CodeChunk> chunks = chunker.chunk("Broken.java", "java", src);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        assertThat(chunks).extracting(CodeChunk::content).anyMatch(c -> c.contains("Broken"));
    }

    @Test
    void parsesRecordsAndSwitchPatterns() {
        String src = """
                package demo;

                public record Point(int x, int y) {
                    public double dist() {
                        return Math.sqrt(x * x + y * y);
                    }
                }

                class Shapes {
                    static String describe(Object o) {
                        return switch (o) {
                            case Point p when p.x() == 0 -> "on the y axis";
                            case Point p -> "point";
                            case String s -> "text " + s;
                            default -> "unknown";
                        };
                    }
                }
                """;
        List<CodeChunk> chunks = chunker.chunk("Point.java", "java", src);

        assertThat(chunks).extracting(CodeChunk::symbol).contains("Point", "Point.dist", "Shapes.describe");
    }
}
