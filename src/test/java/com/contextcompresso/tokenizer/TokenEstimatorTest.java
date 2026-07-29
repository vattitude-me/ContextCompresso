package com.contextcompresso.tokenizer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void jtokkitEstimatesReasonableTokenCount() {
        JtokkitEstimator estimator = new JtokkitEstimator();
        int tokens = estimator.estimate("Hello, world! This is a test.");
        assertThat(tokens).isGreaterThan(0);
        assertThat(tokens).isLessThan(20);
    }

    @Test
    void jtokkitHandlesEmptyString() {
        JtokkitEstimator estimator = new JtokkitEstimator();
        assertThat(estimator.estimate("")).isZero();
    }

    @Test
    void anthropicEstimatorUsesEnglishRatioForProse() {
        AnthropicEstimator estimator = new AnthropicEstimator();
        String prose = "The quick brown fox jumps over the lazy dog in the park today.";
        int tokens = estimator.estimate(prose);
        assertThat(tokens).isEqualTo((int) Math.ceil(prose.length() / 3.2));
    }

    @Test
    void anthropicEstimatorUsesCodeRatioForCode() {
        AnthropicEstimator estimator = new AnthropicEstimator();
        String code = "public class Foo { public void bar() { int x = (1+2)*3; } }";
        int tokens = estimator.estimate(code);
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void charRatioEstimatorUsesFourCharsPerToken() {
        CharRatioEstimator estimator = new CharRatioEstimator();
        String text = "12345678";
        assertThat(estimator.estimate(text)).isEqualTo(2);
    }
}
