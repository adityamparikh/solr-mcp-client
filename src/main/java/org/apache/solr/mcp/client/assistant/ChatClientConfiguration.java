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
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
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
 *
 * <h2>The advisor chain</h2>
 *
 * <p>Three advisors are declared here; a fourth arrives on its own. Ordered outermost first, as
 * they run:
 *
 * <ol>
 *   <li>{@link SessionTurnAdvisor} — order {@code MIN_VALUE + 200}
 *   <li>{@code ToolCallingAdvisor} — order {@code MIN_VALUE + 300}, <em>not declared here</em>
 *   <li>{@link SessionMemoryAdvisor} — order {@code MIN_VALUE + 1000}
 *   <li>{@link SimpleLoggerAdvisor} — order {@code 0}
 * </ol>
 *
 * <p>The two session advisors come as a pair from {@link SessionAdvisors}, which replaced the
 * deprecated {@code ChatMemory} API in Spring AI 2.1. They split what the old
 * {@code MessageChatMemoryAdvisor} did alone: the turn advisor opens the session and owns the whole
 * exchange, and the memory advisor reads and writes the history once per model call.
 *
 * <p>{@code ToolCallingAdvisor} is deliberately absent from the code below. Spring AI 2.0 moved tool
 * calling out of {@code ChatClient}'s internals and into the advisor chain, and
 * {@code DefaultChatClientRequestSpec.buildAdvisorChain()} registers the advisor per request unless
 * the chain already holds something implementing {@code ToolAdvisor} — declaring one here would only
 * suppress the framework's and pin defaults that are already the values we want. Note the
 * registration happens on {@code call()}/{@code stream()}, not on {@code prompt()}: a chain read off
 * {@code prompt()} does not yet contain it.
 *
 * <p>The order relationships are load-bearing, and all fail silently rather than loudly:
 *
 * <ul>
 *   <li><b>Turn outside the tool loop, memory inside it.</b> {@code autoRegisterToolCallingAdvisor()}
 *       switches the tool advisor's internal conversation history off when a {@code MemoryAdvisor}
 *       is ordered <em>after</em> it, and the session design depends on exactly that: each round of
 *       the loop is rebuilt from the session, which stores the tool calls and their results as they
 *       happen. With the memory advisor moved outside, the loop would keep its own history as well
 *       and the session would see only the opening request and the final answer. Keep the default
 *       orders {@link SessionAdvisors} assigns.
 *   <li><b>Logger inside the tool loop.</b> {@link SimpleLoggerAdvisor} logs what passes through it,
 *       so sitting inside the tool advisor's recursion is what makes tool negotiation visible: the
 *       initial request, the model's tool-call request, the follow-up carrying tool results, and the
 *       final answer. Its default order of {@code 0} puts it there. Giving it a high precedence
 *       would move it outside and quietly reduce it to logging only the first and last of those.
 * </ul>
 *
 * <p>No advisor here sets a Reactor scheduler, which leaves each on {@code BaseAdvisor}'s
 * {@code boundedElastic} default — the form the Spring AI reference documents, and the one
 * {@link SessionAdvisors} uses for both session advisors when given no scheduler of its own. That default is a
 * static that a GraalVM native image bakes in as null
 * (<a href="https://github.com/spring-projects/spring-ai/issues/4714">spring-ai#4714</a>), failing
 * every advisor's constructor assertion at run time on the native image only. The fix is at the
 * root, in {@code build.gradle.kts}: {@code --initialize-at-run-time} on {@code BaseAdvisor}. It
 * has to live there rather than here, because {@link SimpleLoggerAdvisor} and the per-request
 * {@code ToolCallingAdvisor} offer no way to set a scheduler at all.
 *
 * <p>{@code SimpleLoggerAdvisor} logs at DEBUG and is registered unconditionally; it self-guards
 * with {@code isDebugEnabled()}, so it costs a boolean check per pass until switched on with
 * {@code logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=DEBUG}. Treat
 * that switch as a debugging tool rather than something to leave on: the output is whole prompts and
 * whole Solr tool results, which means user queries and indexed document contents in the log.
 */
@Configuration(proxyBeanMethods = false)
class ChatClientConfiguration {

    private static final String SYSTEM_PROMPT = """
            You are an Apache Solr assistant. Help users search, index, and manage Solr
            collections by using the available Solr MCP tools when they are needed.
            Explain results clearly. Before any destructive or broad data-changing operation,
            explain the impact and ask the user to confirm.
            """;

    /**
     * How many of a session's newest events are replayed into each prompt. Matches the 20-message
     * window of the {@code MessageWindowChatMemory} this replaces; {@link EventFilter#lastN} extends
     * it back to the start of a turn, so a tool call is never replayed without its result.
     */
    static final int HISTORY_WINDOW = 20;

    /**
     * Where conversations are kept: in process, lost on restart, exactly as the in-memory chat
     * memory before it. Spring AI's session auto-configuration builds the {@link SessionService}
     * on this bean. Swap in {@code spring-ai-starter-model-session-jdbc} (and drop this method) for
     * history that survives a restart.
     */
    @Bean
    SessionRepository sessionRepository() {
        return InMemorySessionRepository.builder().build();
    }

    /**
     * The assistant's client: system prompt, session advisors and Solr MCP tools attached as
     * defaults so every prompt built from it carries them. The class Javadoc explains why the
     * injected builder is mutated directly and why no tool-calling advisor appears here.
     */
    @Bean
    ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            SessionService sessionService,
            ToolCallbackProvider mcpToolCallbacks) {
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(SessionAdvisors.builder(sessionService)
                        .memory(memory -> memory.eventFilter(EventFilter.lastN(HISTORY_WINDOW)))
                        .build())
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .defaultTools(mcpToolCallbacks)
                .build();
    }

}