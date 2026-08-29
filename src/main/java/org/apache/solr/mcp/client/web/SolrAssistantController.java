package org.apache.solr.mcp.client.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

    public static final String CONVERSATION_ID_HEADER = "X-AI-Conversation-Id";

    /**
     * Resolved from the {version} path segment by Spring's ApiVersionStrategy rather than matched
     * literally, so {@code v1}, {@code 1}, {@code 1.0} and {@code 1.0.0} all reach this version.
     */
    static final String V1 = "1.0";

    static final int MAX_MESSAGE_LENGTH = 8_000;
    static final int MAX_CONVERSATION_ID_LENGTH = 128;

    private final SolrAssistant assistant;

    public SolrAssistantController(SolrAssistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping(path = "/chat", version = V1, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ask the Solr assistant a question",
            description = "Sends a message to the chat model with the Solr MCP tools attached. Turns "
                    + "are retained per conversation and replayed on later requests that carry the "
                    + "same X-AI-Conversation-Id. The id used is returned in that header; omit it "
                    + "on the request to start a new conversation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The assistant's answer"),
            @ApiResponse(responseCode = "400", description = "The request failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "The chat model or Solr MCP server rejected the request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "The chat model or Solr MCP server did not respond in time",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    ResponseEntity<ChatResponse> chat(
            @Parameter(description = "Conversation to continue. Omit to start a new one; the id "
                    + "used is always returned in this same response header.",
                    example = "user-7:session-4")
            @RequestHeader(name = CONVERSATION_ID_HEADER, required = false)
            @Size(max = MAX_CONVERSATION_ID_LENGTH, message = "conversationId is too long")
            String conversationId,
            @Valid @RequestBody ChatRequest request) {

        String conversation = StringUtils.hasText(conversationId)
                ? conversationId
                : UUID.randomUUID().toString();
        String answer = assistant.ask(conversation, request.message());

        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, conversation)
                .body(new ChatResponse(answer));
    }

    @DeleteMapping(path = "/chat/{conversationId}", version = V1)
    @Operation(summary = "Forget a conversation",
            description = "Drops the retained turns for a conversation. Chat memory is held in "
                    + "process and is never evicted on its own, so long-lived deployments should "
                    + "release conversations when a session ends.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The conversation was released"),
            @ApiResponse(responseCode = "400", description = "The conversation id failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))})
    ResponseEntity<Void> forget(@PathVariable
                                @NotBlank(message = "conversationId must not be blank")
                                @Size(max = MAX_CONVERSATION_ID_LENGTH, message = "conversationId is too long")
                                String conversationId) {
        assistant.forget(conversationId);
        return ResponseEntity.noContent().build();
    }

    @Schema(description = "A question for the Solr assistant")
    public record ChatRequest(
            @Schema(description = "The natural-language request",
                    example = "How many documents are in the books collection?")
            @NotBlank(message = "message must not be blank")
            @Size(max = MAX_MESSAGE_LENGTH, message = "message is too long")
            String message) {
    }

    @Schema(description = "The assistant's answer")
    public record ChatResponse(
            @Schema(description = "The model's response text")
            String content) {
    }
}
