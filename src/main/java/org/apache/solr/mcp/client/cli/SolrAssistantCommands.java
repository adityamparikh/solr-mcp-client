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
package org.apache.solr.mcp.client.cli;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatRequest;
import org.apache.solr.mcp.client.assistant.SolrAssistant.EmptyAnswerException;
import org.jline.terminal.Terminal;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.web.client.RestClientException;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.UUID;

/**
 * The shell's commands, mapped directly onto {@link SolrAssistant}.
 *
 * <p>{@link CommandGroup} makes these one group rather than three loose top-level words. Its
 * {@code prefix} is prepended to every {@code @Command} name declared here, so the shell answers
 * to {@code solr-mcp chat}, {@code solr-mcp stream} and {@code solr-mcp new} — which is what keeps
 * verbs this general from colliding with Spring Shell's own built-ins ({@code help}, {@code clear},
 * {@code version}, {@code script}) or with whatever a later feature adds. Its {@code name} is
 * unrelated to invocation: it is the heading the three appear under in {@code help}.
 *
 * <p>One conversation id is generated when the shell session starts and every {@code solr-mcp chat}
 * continues it, so follow-up questions resolve against the retained turns exactly as they do for a
 * REST caller holding an {@code X-AI-Conversation-Id}. The {@code solr-mcp new} command releases
 * those turns and starts over. The id is mutable singleton state, which is safe here because the
 * interactive shell evaluates one command at a time on a single thread; chat memory itself is
 * in-process, so ending the JVM reclaims the final conversation without an explicit hook.
 *
 * <p>Spring Shell registers any bean whose methods carry {@link Command}, honouring
 * {@code @Profile} — so outside the {@code cli} profile these commands do not exist, and no
 * registration annotation is needed on the application class. {@code @CommandGroup} is itself a
 * {@code @Component} stereotype, so it is also what makes this class a bean; a second stereotype
 * would be redundant.
 */
@Profile("cli")
@CommandGroup(name = "Solr MCP Commands", prefix = "solr-mcp")
class SolrAssistantCommands {

    private static final String EMPTY_ANSWER_LINE =
            "The model returned no content. Try rephrasing, or `solr-mcp new` to start a fresh conversation.";

    private final SolrAssistant assistant;

    /**
     * The shell's own terminal, written to directly by {@link #stream}. A {@code @Command} that
     * returns text cannot print it until it has all of it, which is precisely what the streaming
     * command must not do; writing here is what lets the answer appear as it is produced. Spring
     * Shell contributes this bean, and it is the same terminal the prompt uses, so output cannot
     * interleave with it.
     */
    private final Terminal terminal;

    private String conversationId = UUID.randomUUID().toString();

    SolrAssistantCommands(SolrAssistant assistant, Terminal terminal) {
        this.assistant = assistant;
        this.terminal = terminal;
    }

    /**
     * Asks the assistant a question, continuing this session's conversation.
     *
     * <p>The question is one quoted argument: {@code solr-mcp chat "which collections exist"}. A
     * {@code String} parameter is deliberate — Spring Shell binds {@code @Arguments} by collecting
     * the remaining tokens and converting them to the parameter type, and the list-to-string
     * conversion joins tokens with {@code ", "}, so unquoted multi-word input would reach the
     * model as {@code "which, collections, exist"}. Quoting keeps the sentence intact.
     *
     * <p>Blank input is answered with a usage hint instead of being sent on: {@link ChatRequest}
     * declares its message {@code @NotBlank}, and unlike the REST path no validator runs here, so
     * this guard is what keeps that contract honest.
     *
     * <p>{@link EmptyAnswerException} is caught and reported as a line of text — this adapter's
     * mapping of the condition, as the REST facade's is a 502. The wider upstream catch below is
     * the same taxonomy the facade's {@code ProblemDetailExceptionHandler} maps to 502/504
     * (transport drops, model/tool errors, a service token that could not be obtained); the
     * exception's own message is included in the returned line because the {@code cli} profile
     * turns console logging off, so this text is the only diagnostics the operator sees. Anything
     * outside that taxonomy — a genuine bug — still propagates to Spring Shell's error rendering.
     * Blocking for the whole exchange is inherent: the answer may involve several model and tool
     * round-trips.
     *
     * @param input the question, as one (usually quoted) argument
     * @return the assistant's answer, or a hint/failure line
     */
    @Command(name = "chat", description = "Chat with the Solr assistant; the conversation continues across asks")
    public String chat(@Arguments String input) {
        if (input.isBlank()) {
            return "Nothing to ask. Try: solr-mcp chat \"which collections exist\"";
        }
        try {
            return assistant.send(conversationId, new ChatRequest(input)).content();
        } catch (EmptyAnswerException e) {
            return EMPTY_ANSWER_LINE;
        } catch (RestClientException | McpError | McpTransportException | ToolExecutionException
                 | ToolCallLimitExceededException | OAuth2AuthorizationException e) {
            return upstreamFailureLine(e);
        }
    }

    /**
     * Asks the assistant the same way {@link #chat} does, printing the answer as the model
     * produces it instead of when it is finished.
     *
     * <p>Returns {@code void} and writes to the terminal itself, because a returned {@code String}
     * could only be printed once the whole answer had arrived. It still blocks for the whole
     * exchange — the shell is one conversation at a time — so the only difference a user sees is
     * when the text appears. On a question that makes the model read a schema and then query, that
     * is the difference between a blank screen and a visible answer.
     *
     * <p>Only the final answer streams: Spring AI resolves the tool calls underneath
     * {@link SolrAssistant#stream} without surfacing them, so the wait for the tool round-trips is
     * as silent here as it is on the blocking command.
     *
     * <p>Failures are mapped exactly as {@link #chat} maps them, with one addition the blocking
     * path cannot have: a stream can fail after emitting text. Whatever already arrived is left on
     * screen — it is what the model actually said — and the failure is reported beneath it.
     *
     * @param input the question, as one (usually quoted) argument
     */
    @Command(name = "stream", description = "Ask the Solr assistant, printing the answer as it arrives")
    public void stream(@Arguments String input) {
        PrintWriter out = terminal.writer();
        if (input.isBlank()) {
            out.println("Nothing to ask. Try: solr-mcp stream \"which collections exist\"");
            terminal.flush();
            return;
        }
        try {
            assistant.stream(conversationId, new ChatRequest(input))
                    // Flushed through the terminal, not the writer, on every delta. Text would
                    // otherwise sit in a buffer until the answer completed — exactly what this
                    // command exists to avoid — and PrintWriter.flush() alone does not reach the
                    // terminal's own output stream.
                    .doOnNext(delta -> {
                        out.print(delta);
                        terminal.flush();
                    })
                    .blockLast();
            out.println();
        } catch (EmptyAnswerException e) {
            out.println();
            out.println(EMPTY_ANSWER_LINE);
        } catch (RestClientException | McpError | McpTransportException | ToolExecutionException
                 | ToolCallLimitExceededException | OAuth2AuthorizationException e) {
            out.println();
            out.println(upstreamFailureLine(e));
        }
        terminal.flush();
    }

    /**
     * The shared wording for the upstream taxonomy both commands catch — the same conditions the
     * REST facade maps to 502 and 504.
     *
     * <p>The exception's own message is included because the {@code cli} profile turns console
     * logging off, so this line is the only diagnostics the operator sees.
     */
    private static String upstreamFailureLine(Exception failure) {
        return "The request failed upstream: "
                + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())
                + "\nThe session continues — check the Solr MCP server (and, under mcp-http, the"
                + " service credentials), then try again.";
    }

    /**
     * Forgets the current conversation and starts a fresh one.
     *
     * <p>The old conversation's retained turns are released eagerly rather than left for JVM exit:
     * chat memory is in-process and never evicted on its own, and a long-lived shell session could
     * otherwise accumulate every conversation it ever abandoned.
     *
     * @return confirmation that the next {@code solr-mcp chat} starts from a clean slate
     */
    @Command(name = "new", description = "Forget the current conversation and start a fresh one")
    public String startNewConversation() {
        assistant.forget(conversationId);
        conversationId = UUID.randomUUID().toString();
        return "Started a new conversation.";
    }
}
