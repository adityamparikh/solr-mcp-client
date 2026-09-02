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
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.shell.core.command.annotation.Arguments;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Objects;
import java.util.UUID;

/**
 * The shell's commands, mapped directly onto {@link SolrAssistant}.
 *
 * <p>One conversation id is generated when the shell session starts and every {@code ask}
 * continues it, so follow-up questions resolve against the retained turns exactly as they do for a
 * REST caller holding an {@code X-AI-Conversation-Id}. The {@code new} command releases those
 * turns and starts over. The id is mutable singleton state, which is safe here because the
 * interactive shell evaluates one command at a time on a single thread; chat memory itself is
 * in-process, so ending the JVM reclaims the final conversation without an explicit hook.
 *
 * <p>Spring Shell registers any bean whose methods carry
 * {@link org.springframework.shell.core.command.annotation.Command}, honouring {@code @Profile} —
 * so outside the {@code cli} profile these commands do not exist, and no registration annotation
 * is needed on the application class.
 */
@Component
@Profile("cli")
class SolrAssistantCommands {

    private final SolrAssistant assistant;
    private String conversationId = UUID.randomUUID().toString();

    SolrAssistantCommands(SolrAssistant assistant) {
        this.assistant = assistant;
    }

    /**
     * Asks the assistant a question, continuing this session's conversation.
     *
     * <p>The question is one quoted argument: {@code solr-mcp "which collections exist"}. A
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
    @Command(name = "solr-mcp", description = "Ask the Solr assistant; the conversation continues across asks")
    public String solrMcp(@Arguments String input) {
        if (input.isBlank()) {
            return "Nothing to ask. Try: solr-mcp \"which collections exist\"";
        }
        try {
            return assistant.send(conversationId, new ChatRequest(input)).content();
        } catch (EmptyAnswerException e) {
            return "The model returned no content. Try rephrasing, or `new` to start a fresh conversation.";
        } catch (RestClientException | McpError | McpTransportException | ToolExecutionException
                 | ToolCallLimitExceededException | OAuth2AuthorizationException e) {
            return "The request failed upstream: "
                    + Objects.toString(e.getMessage(), e.getClass().getSimpleName())
                    + "\nThe session continues — check the Solr MCP server (and, under mcp-http, the"
                    + " service credentials), then try again.";
        }
    }

    /**
     * Forgets the current conversation and starts a fresh one.
     *
     * <p>The old conversation's retained turns are released eagerly rather than left for JVM exit:
     * chat memory is in-process and never evicted on its own, and a long-lived shell session could
     * otherwise accumulate every conversation it ever abandoned.
     *
     * @return confirmation that the next {@code ask} starts from a clean slate
     */
    @Command(name = "new", description = "Forget the current conversation and start a fresh one")
    public String startNewConversation() {
        assistant.forget(conversationId);
        conversationId = UUID.randomUUID().toString();
        return "Started a new conversation.";
    }
}
