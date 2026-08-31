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

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpTransportException;
import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Maps failures onto RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Upstream failures (chat model, MCP server, token endpoint) are reported with a generic detail
 * and logged server-side: their messages routinely carry endpoint URLs and provider payloads that
 * should not reach an API caller.
 */
@RestControllerAdvice
class ProblemDetailExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);

    private static final String UNAVAILABLE_DETAIL =
            "The Solr assistant is temporarily unavailable. Please retry.";
    private static final String FAILED_DETAIL =
            "The Solr assistant could not complete the request.";
    private static final String VALIDATION_FALLBACK = "Request validation failed";

    private static final String ERROR_TYPE_BASE = "https://solr.apache.org/mcp-client/errors/";
    private static final URI VALIDATION_TYPE = URI.create(ERROR_TYPE_BASE + "validation-failed");
    private static final URI UPSTREAM_UNAVAILABLE_TYPE = URI.create(ERROR_TYPE_BASE + "upstream-unavailable");
    private static final URI UPSTREAM_FAILED_TYPE = URI.create(ERROR_TYPE_BASE + "upstream-failed");

    /**
     * Constraint violations on the request body. Overridden rather than added as a second
     * {@code @ExceptionHandler}: the base class already maps this exception, and declaring it twice
     * in one advice fails at startup as an ambiguous mapping. Kept separate from
     * {@link #invalidParameter} because Spring raises a different exception for each, with no
     * common supertype carrying the messages — the two differ only in how the errors are reached.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception, invalidBody(exception), headers, status, request);
    }

    static ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /**
     * Constraint violations on headers and path variables — the annotated method parameters rather
     * than the body. The messages are the annotations' own, so they are safe to return verbatim;
     * unlike upstream failures, they describe the caller's request and disclose nothing.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception, invalidParameter(exception), headers, status, request);
    }

    static ProblemDetail invalidParameter(HandlerMethodValidationException exception) {
        String detail = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /** Retryable upstream conditions: an I/O timeout, or a transport that dropped mid-call. */
    @ExceptionHandler({ResourceAccessException.class, McpTransportException.class})
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    ProblemDetail handleUpstreamUnavailable(RuntimeException exception) {
        log.warn("Transient upstream failure serving a chat request", exception);
        return problem(HttpStatus.GATEWAY_TIMEOUT, UNAVAILABLE_DETAIL,
                "Upstream Unavailable", UPSTREAM_UNAVAILABLE_TYPE);
    }

    /**
     * Non-retryable upstream conditions: a rejected model request, an MCP protocol or tool error,
     * a tool-call loop that hit its ceiling, or a service token that could not be obtained.
     *
     * <p>{@link SolrAssistant.EmptyAnswerException} joins them because a model that completes
     * without content has produced an upstream answer this application cannot use. Catching that
     * exact type rather than its {@code IllegalStateException} supertype is deliberate: Spring
     * raises a bare {@code IllegalStateException} for an unparseable API version segment, which
     * must stay a 400 about the request rather than becoming a 502 about the upstream.
     */
    @ExceptionHandler({RestClientException.class, McpError.class, ToolExecutionException.class,
            ToolCallLimitExceededException.class, OAuth2AuthorizationException.class,
            SolrAssistant.EmptyAnswerException.class})
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ProblemDetail handleUpstreamFailure(RuntimeException exception) {
        log.error("Upstream failure serving a chat request", exception);
        return problem(HttpStatus.BAD_GATEWAY, FAILED_DETAIL,
                "Upstream Request Failed", UPSTREAM_FAILED_TYPE);
    }

    private static ProblemDetail badRequest(String detail) {
        return problem(HttpStatus.BAD_REQUEST, detail.isEmpty() ? VALIDATION_FALLBACK : detail,
                "Request Validation Failed", VALIDATION_TYPE);
    }

    /**
     * Every problem carries a {@code type}, a {@code title} and a {@code timestamp} beyond the
     * status and detail RFC 9457 requires. The type is the stable, machine-readable name of the
     * failure — a client should branch on it rather than on the prose in {@code detail}, which is
     * free to be reworded. It identifies the failure; it is not a promise of a live page. The
     * timestamp is what correlates a caller's response with the server-side log line, which is the
     * only place the real upstream message is written.
     */
    private static ProblemDetail problem(HttpStatus status, String detail, String title, URI type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(type);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
