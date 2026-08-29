package org.apache.solr.mcp.client.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Solr assistant's {@link ChatClient} from the auto-configured {@link ChatClient.Builder}.
 *
 * <p>Starting from the auto-configured builder rather than from a {@code ChatModel} keeps Spring
 * AI's own {@code ChatClientBuilderConfigurer} in play — observation wiring and the tool-calling
 * advisor — which constructing a client by hand would bypass.
 *
 * <p>The builder is configured directly rather than through a {@code ChatClientBuilderCustomizer}.
 * A customizer is applied to <em>every</em> {@code ChatClient.Builder} in the application, so this
 * assistant's system prompt, memory and Solr tools would attach themselves to any builder later
 * injected for an unrelated job. Mutating the injected builder is safe because Spring AI declares
 * it {@code @Scope("prototype")}: each injection point receives its own instance.
 *
 * <p>The {@link ToolCallbackProvider} is handed over as-is instead of expanding
 * {@code getToolCallbacks()} eagerly, so the MCP tool list is resolved when the model first needs
 * it rather than while the application context is still starting.
 */
@Configuration(proxyBeanMethods = false)
class ChatClientConfiguration {

    private static final String SYSTEM_PROMPT = """
            You are an Apache Solr assistant. Help users search, index, and manage Solr
            collections by using the available Solr MCP tools when they are needed.
            Explain results clearly. Before any destructive or broad data-changing operation,
            explain the impact and ask the user to confirm.
            """;

    @Bean
    ChatClient solrMcpChatClient(ChatClient.Builder builder,
                                 ChatMemory chatMemory,
                                 ToolCallbackProvider mcpToolCallbacks) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(mcpToolCallbacks)
                .build();
    }
}
