package org.apache.solr.mcp.client.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Solr assistant's {@link ChatClient} from the auto-configured {@link ChatClient.Builder}.
 *
 * <p>Starting from the auto-configured builder rather than from a {@code ChatModel} keeps Spring
 * AI's own {@code ChatClientBuilderConfigurer} in play — observation wiring and the tool-calling
 * advisor — which constructing a client by hand would bypass. Which provider backs that builder is
 * settled by {@code org.apache.solr.mcp.client.model}; nothing here needs to know.
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

    private static final String NO_MODEL = """
            No chat model is configured. Set OPENAI_API_KEY or ANTHROPIC_API_KEY, or name a \
            provider explicitly with spring.ai.model.chat.""";

    @Bean
    ChatClient solrMcpChatClient(ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                 ChatMemory chatMemory,
                                 ToolCallbackProvider mcpToolCallbacks) {
        // Absent when no provider was activated, meaning no API key was found. Reported here, where
        // a chat model is first required, rather than while post-processing the environment: slices
        // that never touch a model must not be made to care.
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable(() -> {
            throw new IllegalStateException(NO_MODEL);
        });
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(mcpToolCallbacks)
                .build();
    }
}
