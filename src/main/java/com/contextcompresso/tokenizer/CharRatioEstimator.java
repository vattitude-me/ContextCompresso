package com.contextcompresso.tokenizer;

import org.springframework.stereotype.Component;

/**
 * Simple fallback estimator: chars/4, the widely used naive heuristic for generic
 * OpenAI-compatible models when jtokkit isn't configured for a given deployment.
 */
@Component
public class CharRatioEstimator implements TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
