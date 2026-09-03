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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

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

    /**
     * Content type pinned on the streaming response.
     *
     * <p>The charset is not decoration. {@code ReactiveTypeHandler} stamps the media type it is
     * given onto {@code Content-Type} verbatim, so a bare {@code text/plain} would advertise no
     * charset over UTF-8 bytes and invite a client to decode an accented title as ISO-8859-1.
     *
     * <p>Pinning it also fixes which branch of {@code ReactiveTypeHandler} runs. That handler
     * reads the media type from this response's own {@code Content-Type} and only falls back to
     * the request's {@code Accept} when it is absent — so were this left unset, a caller sending
     * {@code Accept: text/event-stream} would be served Server-Sent Events instead, reinstating
     * the framing this endpoint deliberately does without.
     */
    private static final MediaType TEXT_PLAIN_UTF8 =
            new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private static final Logger log = LoggerFactory.getLogger(SolrAssistantController.class);

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
            String conversationId,
            @Valid @RequestBody ChatRequest request) {

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversationId)
                .body(assistant.send(conversationId, request));
    }

    /**
     * The same turn as {@link #chat}, delivered as plain text while the model produces it.
     *
     * <p>The body is the answer and nothing else: deltas are concatenated onto the response as
     * they arrive, so a client appends what it reads and is finished. This is the shape Spring AI
     * documents for a streaming endpoint — a {@code Flux<String>} handed straight back — kept in a
     * {@link ResponseEntity} only so the conversation header can travel with it. Spring MVC
     * unwraps that wrapper before choosing how to stream ({@code ReactiveTypeHandler} calls
     * {@code MethodParameter.nested()}), so the wrapper costs nothing here.
     *
     * <p><strong>It is deliberately not Server-Sent Events.</strong> SSE has no escaping: a frame
     * is {@code data:} followed by the payload as-is. A delta beginning with a space therefore
     * landed where the SSE specification directs a client to strip exactly one, turning
     * {@code "Your"}, {@code " films"} into {@code Yourfilms}; and a delta containing a newline was
     * rewritten to {@code "\ndata:"}, splitting one delta across frames for any client not running
     * a conformant parser. Escaping the payload would have fixed both, at the cost of making every
     * consumer decode a frame to recover text it can otherwise use directly. Plain text has
     * neither hazard and needs no parser.
     *
     * <p><strong>A failure after the first byte truncates the response.</strong>
     * {@link ProblemDetailExceptionHandler} can only act while the response is uncommitted.
     * Failures raised before the first delta — validation, an unsupported version, anything the
     * assistant throws synchronously — still become RFC 9457 problem details with their proper
     * status. Once a delta has flushed the status is already {@code 200} and a plain-text body has
     * no vocabulary for reporting a failure in-band, so the error is propagated: the answer
     * produced so far stands and the response is aborted without its terminating chunk, which is
     * what tells a client the answer is incomplete. Injecting a human-readable marker instead was
     * rejected because it would leave a program unable to tell a truncated answer from a finished
     * one. The cause is logged here, because nothing else records it.
     */
    @PostMapping(path = "/stream", version = V1, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<Flux<String>> stream(
            @Parameter(description = "Conversation to continue; omit to start a new one. Always returned.")
            @RequestHeader(name = CONVERSATION_ID_HEADER, defaultValue = NEW_CONVERSATION_ID)
            String conversationId,
            @Valid @RequestBody ChatRequest request) {

        Flux<String> answer = assistant.stream(conversationId, request)
                .doOnError(failure ->
                        log.error("Upstream failure serving a streamed chat request", failure));

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversationId)
                .contentType(TEXT_PLAIN_UTF8)
                .body(answer);
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
