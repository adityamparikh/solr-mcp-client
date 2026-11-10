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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.advisor.SessionTurnAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the advisor chain the assistant's {@link ChatClient} is built with.
 *
 * <p>Builds through {@link ChatClient#builder(ChatModel)} rather than a mocked
 * {@code ChatClient.Builder} so the assertions run against the chain Spring AI actually assembles —
 * including the {@link ToolCallingAdvisor} it registers on its own, which a mocked builder would
 * never produce.
 *
 * <p>That auto-registration happens in {@code buildAdvisorChain()}, reached from {@code call()}
 * rather than from {@code prompt()}, so the ordering tests drive one exchange against a stub model
 * before reading the chain back off the request spec the call mutated — a chain read off
 * {@code prompt()} would be missing the very advisor they are about.
 *
 * <p>The session advisors run against a real {@link SessionService} on an in-memory repository rather
 * than a mock, so the tests see what the session actually stores and replays.
 *
 * <p>The last tests go further and drive a real tool round-trip through that chain, which is what
 * proves the logging and the session storage are worth having rather than merely correctly ordered.
 */
class ChatClientConfigurationTest {

    private final ChatClientConfiguration configuration = new ChatClientConfiguration();

    private final SessionService sessionService = DefaultSessionService.builder()
            .sessionRepository(InMemorySessionRepository.builder().build())
            .build();

    /**
     * Stands in for the options a real provider's {@code ChatModel} contributes to every request.
     *
     * <p>The type matters and is not interchangeable with {@code ChatOptions.builder()}:
     * {@code ToolCallingAdvisor.adviseCall} opens with an
     * {@code instanceof ToolCallingChatOptions} check and, when it fails, delegates straight down
     * the chain without running the tool loop at all — no error, no log, tools simply never called.
     * A stub built with plain {@code ChatOptions} therefore makes tool-calling tests pass vacuously.
     *
     * @return options Spring AI will recognise as tool-capable
     */
    private ChatOptions toolCapableOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    /**
     * @return the advisors present once Spring AI has finished assembling the chain for a real
     * request, in the order the configuration and the framework contributed them
     */
    private List<Advisor> assembledAdvisors() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(toolCapableOptions());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ok")))));

        ToolCallbackProvider toolCallbacks = mock(ToolCallbackProvider.class);
        when(toolCallbacks.getToolCallbacks()).thenReturn(new ToolCallback[0]);

        ChatClient chatClient =
                configuration.chatClient(ChatClient.builder(chatModel), sessionService, toolCallbacks);

        // The session id is supplied per request by SolrAssistant, never defaulted, so the
        // session advisors reject the call without it.
        var requestSpec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt()
                .user("hello")
                .advisors(SessionAdvisors.session("test"));
        requestSpec.call().content();

        return requestSpec.getAdvisors();
    }

    @Test
    void registersASimpleLoggerAdvisorSoTheLlmExchangeCanBeLogged() {
        assertThat(assembledAdvisors()).hasAtLeastOneElementOfType(SimpleLoggerAdvisor.class);
    }

    /**
     * The whole point of pairing the logger with tool calling: an advisor ordered <em>after</em> the
     * tool advisor sits inside its recursive loop and therefore sees the tool-negotiation
     * round-trips, not just the opening request and the final answer. Ordering the logger ahead of
     * the tool advisor would silently drop it back to two log entries per exchange.
     */
    @Test
    void ordersTheLoggerInsideTheToolCallingLoopSoToolTrafficIsLogged() {
        List<Advisor> advisors = assembledAdvisors();

        Advisor logger = advisors.stream()
                .filter(SimpleLoggerAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow();
        Advisor toolCalling = advisors.stream()
                .filter(ToolCallingAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertThat(logger.getOrder()).isGreaterThan(toolCalling.getOrder());
    }

    /**
     * The session turn wraps the whole tool loop and the session memory runs inside it. Spring AI
     * turns the tool advisor's internal conversation history off when a memory advisor is ordered
     * after it, which is what the session design wants: each round of the loop is rebuilt from the
     * session, which stores the tool calls and results as they happen. Moving the memory advisor
     * outside would leave the session holding only the opening request and the final answer.
     */
    @Test
    void wrapsTheToolLoopInTheSessionTurnAndRunsSessionMemoryInsideIt() {
        List<Advisor> advisors = assembledAdvisors();

        Advisor turn = advisors.stream()
                .filter(SessionTurnAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow();
        Advisor toolCalling = advisors.stream()
                .filter(ToolCallingAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow();
        Advisor memory = advisors.stream()
                .filter(SessionMemoryAdvisor.class::isInstance)
                .findFirst()
                .orElseThrow();

        assertThat(turn.getOrder()).isLessThan(toolCalling.getOrder());
        assertThat(memory.getOrder()).isGreaterThan(toolCalling.getOrder());
    }

    /**
     * What memory is for, asserted on the prompt the model receives: a second turn in the same
     * session carries the first turn's question and answer, and a different session does not.
     */
    @Test
    void replaysEarlierTurnsOfTheSameSessionOnly() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(toolCapableOptions());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Noted: films.")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("You said films.")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("I don't know.")))));
        ToolCallbackProvider toolCallbacks = mock(ToolCallbackProvider.class);
        when(toolCallbacks.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        ChatClient chatClient =
                configuration.chatClient(ChatClient.builder(chatModel), sessionService, toolCallbacks);

        chatClient.prompt().user("My collection is called films.")
                .advisors(SessionAdvisors.session("alice")).call().content();
        chatClient.prompt().user("Which collection did I mention?")
                .advisors(SessionAdvisors.session("alice")).call().content();
        chatClient.prompt().user("Which collection did I mention?")
                .advisors(SessionAdvisors.session("bob")).call().content();

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(prompts.capture());
        assertThat(texts(prompts.getAllValues().get(1).getInstructions()))
                .contains("My collection is called films.", "Noted: films.");
        assertThat(texts(prompts.getAllValues().get(2).getInstructions()))
                .doesNotContain("My collection is called films.", "Noted: films.");
    }

    /**
     * The reason the logger is registered at all, asserted end to end rather than inferred from
     * ordering: an exchange in which the model calls a tool must log the two intermediate
     * round-trips, not only the opening request and the final answer.
     *
     * <p>Before Spring AI 2.0 this was impossible — tool calling ran inside {@code ChatClient} where
     * no advisor could observe it, and this test would see two entries however the chain was
     * ordered. It fails the same way if the logger is ever moved outside the tool advisor's
     * recursion.
     */
    @Test
    void logsTheToolNegotiationRoundTripsAndNotJustTheEndsOfTheExchange() {
        ListAppender<ILoggingEvent> logged = captureDebugLogsOfSimpleLoggerAdvisor();

        ToolCallback countDocuments = FunctionToolCallback
                .builder("countDocuments", () -> "42")
                .description("Counts the documents in the films collection")
                .inputType(Void.class)
                .build();
        ToolCallbackProvider toolCallbacks = mock(ToolCallbackProvider.class);
        when(toolCallbacks.getToolCallbacks()).thenReturn(new ToolCallback[]{countDocuments});

        AssistantMessage callsTheTool = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "countDocuments", "{}")))
                .build();

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(toolCapableOptions());
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(callsTheTool))),
                new ChatResponse(List.of(new Generation(
                        new AssistantMessage("The films collection holds 42 documents.")))));

        configuration
                .chatClient(ChatClient.builder(chatModel), sessionService, toolCallbacks)
                .prompt()
                .user("how many documents are in the films collection?")
                .advisors(SessionAdvisors.session("test"))
                .call()
                .content();

        // Request, the model's tool-call request, the follow-up carrying the tool result, answer.
        assertThat(logged.list).hasSize(4);
        assertThat(logged.list.get(1).getFormattedMessage()).contains("countDocuments");
        assertThat(logged.list.get(2).getFormattedMessage()).contains("42");

        // The session memory runs inside the loop, so the whole negotiation is in the session, in
        // order: the question, the tool call, its result and the answer.
        assertThat(sessionService.getMessages("test"))
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL,
                        MessageType.ASSISTANT);
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    /**
     * Attaches a capturing appender to {@link SimpleLoggerAdvisor}'s own logger and forces it to
     * DEBUG — the advisor self-guards on {@code isDebugEnabled()}, so at the default level it emits
     * nothing and the assertions above would pass on an empty list for the wrong reason.
     */
    private ListAppender<ILoggingEvent> captureDebugLogsOfSimpleLoggerAdvisor() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        advisorLogger().addAppender(appender);
        advisorLogger().setLevel(Level.DEBUG);
        return appender;
    }

    private Logger advisorLogger() {
        return (Logger) LoggerFactory.getLogger(SimpleLoggerAdvisor.class);
    }

    /**
     * Logback loggers are process-wide, so the capture above would otherwise leave every later test
     * in the JVM writing prompts and tool results to the console.
     */
    @AfterEach
    void restoreTheAdvisorLogger() {
        advisorLogger().detachAndStopAllAppenders();
        advisorLogger().setLevel(null);
    }
}
