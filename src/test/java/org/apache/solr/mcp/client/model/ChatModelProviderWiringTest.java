package org.apache.solr.mcp.client.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real context against Anthropic, proving the second provider starter is wired and
 * reachable — the selection rules themselves are covered by {@link ChatModelProviderSelectorTest},
 * which can vary the environment without depending on the developer's exported keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-key",
        "spring.ai.mcp.client.initialized=false",
        "SOLR_MCP_HTTP_URL=https://solr-mcp.example.com",
        "SOLR_MCP_OAUTH_CLIENT_ID=test-client",
        "SOLR_MCP_OAUTH_CLIENT_SECRET=test-secret",
        "SOLR_MCP_OAUTH_TOKEN_URI=https://idp.example.com/token"
})
@ActiveProfiles("mcp-http")
class ChatModelProviderWiringTest {

    @Autowired
    ChatModel chatModel;

    @Test
    void drivesTheAssistantWithAnthropicWhenThatProviderIsSelected() {
        // Exactly one ChatModel: Spring AI's providers all declare matchIfMissing = true, so
        // leaving spring.ai.model.chat unset would publish one per starter and break the builder.
        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }
}
