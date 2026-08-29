package org.apache.solr.mcp.client.assistant;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class ChatClientConfigurationTest {

    private final ChatClientConfiguration configuration = new ChatClientConfiguration();
    private final ChatClient.Builder builder = mock(ChatClient.Builder.class, Mockito.RETURNS_SELF);
    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final ToolCallbackProvider toolCallbacks = mock(ToolCallbackProvider.class);

    @Test
    void configuresThePromptMemoryAndSolrToolsOnTheAutoConfiguredBuilder() {
        ChatClient built = mock(ChatClient.class);
        given(builder.build()).willReturn(built);

        assertThat(configuration.solrMcpChatClient(builder, chatMemory, toolCallbacks)).isSameAs(built);

        then(builder).should().defaultSystem(contains("Apache Solr assistant"));
        then(builder).should().defaultAdvisors(any(MessageChatMemoryAdvisor.class));
        then(builder).should().defaultTools(toolCallbacks);
    }

    @Test
    void neverResolvesTheMcpToolListWhileTheContextIsStarting() {
        configuration.solrMcpChatClient(builder, chatMemory, toolCallbacks);

        // The provider is passed through, not expanded: an eager getToolCallbacks() would force a
        // connection to the Solr MCP server during context startup.
        then(toolCallbacks).shouldHaveNoInteractions();
    }
}
