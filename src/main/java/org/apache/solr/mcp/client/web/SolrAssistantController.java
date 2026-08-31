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
package org.apache.solr.mcp.client.web;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatReply;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transport-only REST facade over {@link SolrAssistant}.
 *
 * <p>The conversation is carried in the {@code X-AI-Conversation-Id} header, in both directions and
 * nowhere else: it is ambient session context, not part of what the user asked or of what the model
 * answered. Omitting it on a request starts a fresh conversation, and the id used is always echoed
 * on the response so a client can continue it. There is deliberately no shared fallback
 * conversation, because this facade performs no inbound authentication and unrelated callers would
 * otherwise share one memory bucket.
 *
 * <p>A browser can only read this header cross-origin when the server names it in
 * {@code Access-Control-Expose-Headers}; {@link ApiCorsConfiguration} does that wherever CORS
 * applies. Rename the header here and it must be renamed there too, or conversation continuity
 * breaks for cross-origin callers with no error to notice.
 *
 * <p>A conversation id is a routing key, not a secret: any caller that knows one can continue it.
 * Keep the deployment inside a trusted boundary.
 */
@RestController
@RequestMapping("/api/{version}")
@Tag(name = "Solr assistant", description = "Natural-language access to Apache Solr through MCP tools")
public class SolrAssistantController {

    /**
     * Public because {@link ApiCorsConfiguration} must name this header in
     * {@code Access-Control-Expose-Headers} for cross-origin callers to read it back. Renaming it
     * here without renaming it there breaks conversation continuity with no error to notice.
     */
    public static final String CONVERSATION_ID_HEADER = "X-AI-Conversation-Id";

    /** Matched semantically, so {@code v1}, {@code 1}, {@code 1.0} and {@code 1.0.0} all reach it. */
    static final String V1 = "v1";

    /**
     * Default for the conversation header, so an absent header arrives as a fresh id instead of as
     * a {@code null} the handler has to substitute for. Spring re-evaluates a {@code #{...}}
     * default on every argument resolution — it caches the parsed expression, not its result — so
     * this yields a distinct id per request rather than one frozen at startup. Evaluating it needs
     * a bean factory, so it works under an application context but not under
     * {@code MockMvcBuilders.standaloneSetup}, where the expression would reach the handler as
     * literal text.
     *
     * <p>A default only fires when the header is absent, never when it is present but empty, hence
     * the {@link NotBlank} beside it: without that, a blank header would name the shared
     * {@code ""} conversation this facade deliberately does not have.
     */
    static final String NEW_CONVERSATION_ID = "#{T(java.util.UUID).randomUUID().toString()}";

    private final SolrAssistant assistant;

    /**
     * @param assistant the only collaborator; this facade adds transport concerns and nothing
     *                  else, so anything it would need beyond this belongs in the assistant
     */
    public SolrAssistantController(SolrAssistant assistant) {
        this.assistant = assistant;
    }

    /**
     * Answers one conversational turn. The conversation id is echoed unconditionally — for a
     * caller that omitted the header it is the only place the generated id can be learned, and
     * echoing it always spares clients a "was it present" branch.
     */
    @PostMapping(path = "/chat", version = V1, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ChatReply> chat(
            @Parameter(description = "Conversation to continue; omit to start a new one. Always returned.")
            @RequestHeader(name = CONVERSATION_ID_HEADER, defaultValue = NEW_CONVERSATION_ID)
            @NotBlank(message = "conversationId must not be blank")
            String conversationId,
            @Valid @RequestBody ChatRequest request) {

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversationId)
                .body(assistant.ask(conversationId, request));
    }

    /**
     * Releases a conversation's retained turns. Idempotent because
     * {@link SolrAssistant#forget} accepts unknown ids, so a retried DELETE stays a 204.
     */
    @DeleteMapping(path = "/chat/{conversationId}", version = V1)
    ResponseEntity<Void> forget(@PathVariable
                                @NotBlank(message = "conversationId must not be blank")
                                String conversationId) {
        assistant.forget(conversationId);
        return ResponseEntity.noContent().build();
    }
}
