package com.contextcompresso.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionKeyResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameConversationPrefixYieldsSameKeyAsHistoryGrows() throws Exception {
        String turn1 = """
                {"system": "You are helpful.", "messages": [
                  {"role": "user", "content": "hello"}
                ]}
                """;
        String turn2 = """
                {"system": "You are helpful.", "messages": [
                  {"role": "user", "content": "hello"},
                  {"role": "assistant", "content": "hi there"},
                  {"role": "user", "content": "what is 2+2"}
                ]}
                """;

        String key1 = SessionKeyResolver.resolve(mapper.readTree(turn1));
        String key2 = SessionKeyResolver.resolve(mapper.readTree(turn2));

        assertThat(key1).isNotNull();
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void differentConversationsYieldDifferentKeys() throws Exception {
        String a = "{\"messages\": [{\"role\": \"user\", \"content\": \"hello\"}]}";
        String b = "{\"messages\": [{\"role\": \"user\", \"content\": \"goodbye\"}]}";

        String keyA = SessionKeyResolver.resolve(mapper.readTree(a));
        String keyB = SessionKeyResolver.resolve(mapper.readTree(b));

        assertThat(keyA).isNotEqualTo(keyB);
    }

    @Test
    void returnsNullForEmptyOrMissingMessages() throws Exception {
        JsonNode empty = mapper.readTree("{}");
        assertThat(SessionKeyResolver.resolve(empty)).isNull();
    }

    @Test
    void returnsNullForNonObjectRoot() throws Exception {
        JsonNode array = mapper.readTree("[1,2,3]");
        assertThat(SessionKeyResolver.resolve(array)).isNull();
    }
}
