/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * <p>Which provider backs that builder is not decided in code at all. Exactly one
 * {@code spring-ai-starter-model-*} dependency is on the classpath (today
 * {@code spring-ai-starter-model-anthropic}), Spring AI auto-configures the single {@code ChatModel}
 * it finds there, and the builder wraps it. Switching providers is therefore a build-file change
 * plus the matching API key in the environment — swap the starter in {@code build.gradle.kts} and
 * export that provider's key. Nothing in this package names a provider, and nothing here should:
 * depend on {@link ChatClient} and {@link ChatClient.Builder}, never on {@code AnthropicChatModel}
 * or a provider-specific {@code @Qualifier}. If more than one model starter is ever present,
 * {@code spring.ai.model.chat} has to name the winner or startup fails rather than guessing.
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
    ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            ToolCallbackProvider mcpToolCallbacks) {
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(mcpToolCallbacks)
                .build();
    }

}