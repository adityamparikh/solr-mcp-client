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

import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * The Solr assistant, expressed independently of any transport.
 *
 * <p>This is the seam a user interface binds to. The REST facade in
 * {@code org.apache.solr.mcp.client.web} is one adapter over it; an in-process UI layer (Vaadin,
 * Hilla, a CLI runner) injects this bean directly rather than calling the application's own HTTP
 * endpoints. Keeping the chat client, memory scoping and MCP tools here means adding a UI
 * duplicates none of that wiring.
 *
 * <p>{@link ChatRequest} and {@link ChatReply} are the assistant's own vocabulary rather than the
 * REST facade's, for the same reason: an adapter should map its transport onto these types, not
 * define a parallel pair of its own that has to be kept in step. What they require of a turn
 * therefore holds for every adapter, including one that never speaks HTTP.
 */
@Service
public class SolrAssistant {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    /**
     * Both collaborators are singletons shared by every conversation; the conversation id passed to
     * {@link #ask} is what keeps their state separate.
     *
     * @param chatClient the assistant's own client, already carrying the system prompt, memory
     *                   advisor and Solr MCP tools
     * @param chatMemory the same store the advisor writes through, injected so conversations can
     *                   also be released; the advisor alone offers no way to evict one
     */
    public SolrAssistant(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    /**
     * Answers {@code request} in the context of {@code conversationId}, calling Solr MCP tools as
     * the model requires them.
     *
     * <p>Blocks for the whole exchange, tool round-trips included, so a single call may take far
     * longer than one model request.
     *
     * @param conversationId scopes the retained turns; an id never seen before starts a new
     *                       conversation rather than failing
     * @return the model's answer, with any tool calls already resolved
     * @throws EmptyAnswerException if the model completed without producing any content, which
     *                              {@code ChatClient} reports as a null body
     */
    public ChatReply ask(String conversationId, ChatRequest request) {
        String answer = chatClient.prompt()
                .user(request.message())
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        // ChatClient declares content() @Nullable. Returning it unchecked would make this method's
        // own non-null contract a lie under @NullMarked, and the null would surface to a caller as
        // an answer of {"content": null} rather than as the upstream failure it is.
        if (answer == null) {
            throw new EmptyAnswerException(
                    "The chat model returned no content for conversation " + conversationId);
        }
        return new ChatReply(answer);
    }

    /**
     * Drops the retained turns for a conversation. Chat memory is held in process and never evicted
     * on its own, so callers must release conversations they are finished with.
     *
     * @param conversationId the conversation to release; unknown ids are silently accepted, so a
     *                       caller need not track whether a conversation was ever started
     */
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }

    /**
     * The user's turn, and nothing else.
     *
     * <p>The conversation it belongs to is passed alongside it, never carried in this record: it is
     * ambient session context rather than part of what the user asked. How an adapter transports
     * that id is the adapter's business — the REST facade uses a header.
     */
    public record ChatRequest(
            @NotBlank(message = "message must not be blank")
            String message) {
    }

    /**
     * The assistant's answer, already resolved: any tool calls the model made against Solr MCP
     * happened before this was built, so the content is final text and never a pending tool call.
     */
    public record ChatReply(String content) {
    }

    /**
     * The model completed without producing any content.
     *
     * <p>A named type rather than a bare {@link IllegalStateException} so that an adapter can map
     * this condition on its own: the REST facade reports it as a 502, and catching the supertype
     * there would also capture Spring's own {@code IllegalStateException} for an unparseable API
     * version — a client error that must not be reported as an upstream one.
     */
    public static class EmptyAnswerException extends IllegalStateException {

        public EmptyAnswerException(String message) {
            super(message);
        }
    }
}
