package com.contextcompresso.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

/**
 * Exact GPT-style token counts (used for Copilot/OpenAI-compatible models) via jtokkit's
 * cl100k_base encoding, the encoding used by gpt-3.5/gpt-4 family models.
 */
@Component
public class JtokkitEstimator implements TokenEstimator {

    private final Encoding encoding;

    public JtokkitEstimator() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}
