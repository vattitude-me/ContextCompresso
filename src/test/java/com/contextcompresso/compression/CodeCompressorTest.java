package com.contextcompresso.compression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeCompressorTest {

    private final CodeCompressor compressor = new CodeCompressor();

    @Test
    void stripsJavaBlockAndInlineComments() {
        String code = "int x = 1; // inline comment\n/* block\n comment */\nint y = 2;";
        String result = compressor.compressCode(code, "java", false);
        assertThat(result).doesNotContain("inline comment");
        assertThat(result).doesNotContain("block");
        assertThat(result).contains("int x = 1;");
        assertThat(result).contains("int y = 2;");
    }

    @Test
    void stripsPythonHashComments() {
        String code = "x = 1  # a comment\ny = 2";
        String result = compressor.compressCode(code, "python", false);
        assertThat(result).doesNotContain("a comment");
        assertThat(result).contains("x = 1");
        assertThat(result).contains("y = 2");
    }

    @Test
    void preservesStringLiteralsContainingSlashSlash() {
        String code = "String url = \"http://example.com\";";
        String result = compressor.compressCode(code, "java", false);
        assertThat(result).contains("http://example.com");
    }

    @Test
    void preservesHashInsideStringLiteral() {
        String code = "color = \"#fff\"";
        String result = compressor.compressCode(code, "python", false);
        assertThat(result).contains("#fff");
    }

    @Test
    void unknownLanguageOnlyCollapsesBlankLines() {
        String code = "// not a real comment strip target\n\n\n\nline2";
        String result = compressor.compressCode(code, "cobol", false);
        assertThat(result).contains("// not a real comment strip target");
        assertThat(result).doesNotContain("\n\n\n\n");
    }

    @Test
    void copilotAggressiveModeStripsImports() {
        String code = "import java.util.List;\n\nclass Foo {}\n";
        String result = compressor.compressCode(code, "java", true);
        assertThat(result).doesNotContain("import java.util.List;");
        assertThat(result).contains("class Foo {}");
    }

    @Test
    void collapsesThreeOrMoreBlankLinesToTwoInMarkdown() {
        String markdown = "```java\nint a = 1;\n\n\n\nint b = 2;\n```";
        String result = compressor.compressMarkdown(markdown, false);
        assertThat(result).doesNotContain("\n\n\n");
    }
}
