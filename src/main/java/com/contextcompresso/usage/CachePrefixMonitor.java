package com.contextcompresso.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.contextcompresso.util.HashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Detects when a session's cached prompt prefix silently breaks. Anthropic's prompt caching
 * matches on an exact byte prefix; if a client sends a request whose system prompt or earlier
 * messages differ from what it sent last turn for the same conversation — an edited message,
 * a client that doesn't resend history byte-for-byte, a summarization step upstream — every
 * subsequent cache_read_input_tokens for that prefix reverts to full-price input_tokens,
 * roughly a 10x cost jump on exactly the tokens caching was relying on to be cheap. This is
 * plausibly a bigger drain than anything the compression pipeline recovers, and unlike
 * compression it is invisible unless something is watching for it.
 *
 * <p>Correctness hinges on comparing the same range of messages across turns, not a fixed
 * index window: message index 1 is empty on turn 1 (just the user's opener) and holds the
 * assistant's reply by turn 2, so naively hashing "the first N messages" flags ordinary
 * conversation growth as a divergence on every session's second turn. Instead, each check
 * hashes messages [0, min(previousMessageCount, currentMessageCount)) — the overlap that is
 * actually expected to be byte-identical — and stores the current message count alongside the
 * hash so the next comparison uses the correct overlap again.
 */
@Component
public class CachePrefixMonitor {

    private static final Logger log = LoggerFactory.getLogger(CachePrefixMonitor.class);

    private record Snapshot(String hash, int messageCount) {
    }

    private final Cache<String, Snapshot> lastSnapshotBySession;

    public CachePrefixMonitor() {
        this.lastSnapshotBySession = Caffeine.newBuilder()
                .expireAfterAccess(2, TimeUnit.HOURS)
                .maximumSize(10_000)
                .build();
    }

    public record PrefixCheck(boolean diverged, boolean firstSeen) {
        public static final PrefixCheck FIRST_SEEN = new PrefixCheck(false, true);
        public static final PrefixCheck STABLE = new PrefixCheck(false, false);
        public static final PrefixCheck DIVERGED = new PrefixCheck(true, false);
    }

    /**
     * Call once per request. Records a snapshot of the current prefix for next time
     * regardless of outcome, so a divergence is only ever reported once per actual change,
     * not repeated on every subsequent turn.
     */
    public PrefixCheck check(String sessionKey, JsonNode root) {
        if (sessionKey == null || root == null || !root.isObject()) {
            return PrefixCheck.FIRST_SEEN;
        }

        int currentMessageCount = messageCount(root);
        Snapshot previous = lastSnapshotBySession.getIfPresent(sessionKey);

        if (previous == null) {
            lastSnapshotBySession.put(sessionKey, new Snapshot(hashPrefix(root, currentMessageCount), currentMessageCount));
            return PrefixCheck.FIRST_SEEN;
        }

        int overlap = Math.min(previous.messageCount(), currentMessageCount);
        String currentHash = hashPrefix(root, overlap);
        lastSnapshotBySession.put(sessionKey, new Snapshot(hashPrefix(root, currentMessageCount), currentMessageCount));

        if (currentHash == null || previous.hash() == null) {
            return PrefixCheck.STABLE;
        }
        // previous.hash() was computed over previous.messageCount() messages; only comparable
        // to currentHash when the overlap equals what was actually stored last time
        if (overlap < previous.messageCount()) {
            // conversation shrank (e.g. a fresh session reusing a stale key) — not a
            // same-session divergence in the sense this monitor cares about
            return PrefixCheck.STABLE;
        }
        if (!previous.hash().equals(currentHash)) {
            log.warn("Cache prefix diverged for sessionKey={} — cache_read_input_tokens for this " +
                    "conversation's prefix will likely revert to full-price input_tokens until a new " +
                    "prefix is established", sessionKey);
            return PrefixCheck.DIVERGED;
        }
        return PrefixCheck.STABLE;
    }

    private int messageCount(JsonNode root) {
        JsonNode messages = root.get("messages");
        return messages != null && messages.isArray() ? messages.size() : 0;
    }

    private String hashPrefix(JsonNode root, int messageLimit) {
        StringBuilder basis = new StringBuilder();
        JsonNode system = root.get("system");
        if (system != null) {
            basis.append(system.isTextual() ? system.asText() : system.toString());
        }
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray()) {
            int limit = Math.min(messageLimit, messages.size());
            for (int i = 0; i < limit; i++) {
                basis.append('|').append(messages.get(i).toString());
            }
        }
        if (basis.isEmpty()) {
            return null;
        }
        return HashUtil.sha256Hex(basis.toString());
    }
}
