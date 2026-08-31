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
     * Answers {@code message} in the context of {@code conversationId}, calling Solr MCP tools as
     * the model requires them.
     *
     * <p>Blocks for the whole exchange, tool round-trips included, so a single call may take far
     * longer than one model request.
     *
     * @param conversationId scopes the retained turns; an id never seen before starts a new
     *                       conversation rather than failing
     * @param message        the user's turn
     * @return the model's answer, with any tool calls already resolved
     */
    public String ask(String conversationId, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
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
}
