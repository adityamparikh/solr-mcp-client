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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.stream.Collectors;

/**
 * Maps failures onto RFC 9457 {@link ProblemDetail} responses.
 *
 * <p>Upstream failures (chat model, MCP server, token endpoint) are reported with a generic detail
 * and logged server-side: their messages routinely carry endpoint URLs and provider payloads that
 * should not reach an API caller.
 */
@RestControllerAdvice
class ProblemDetailExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);

    private static final String UNAVAILABLE_DETAIL =
            "The Solr assistant is temporarily unavailable. Please retry.";
    private static final String FAILED_DETAIL =
            "The Solr assistant could not complete the request.";
    private static final String VALIDATION_FALLBACK = "Request validation failed";

    /**
     * Constraint violations on the request body. Kept separate from
     * {@link #handleInvalidParameter} because Spring raises a different exception for each, with no
     * common supertype carrying the messages — the two differ only in how the errors are reached.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /**
     * Constraint violations on headers and path variables — the annotated method parameters rather
     * than the body. The messages are the annotations' own, so they are safe to return verbatim;
     * unlike upstream failures, they describe the caller's request and disclose nothing.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleInvalidParameter(HandlerMethodValidationException exception) {
        String detail = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        return badRequest(detail);
    }

    /** Retryable upstream conditions: an I/O timeout, or a transport that dropped mid-call. */
    @ExceptionHandler({ResourceAccessException.class, McpTransportException.class})
    ProblemDetail handleUpstreamUnavailable(RuntimeException exception) {
        log.warn("Transient upstream failure serving a chat request", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, UNAVAILABLE_DETAIL);
    }

    /**
     * Non-retryable upstream conditions: a rejected model request, an MCP protocol or tool error,
     * a tool-call loop that hit its ceiling, or a service token that could not be obtained.
     */
    @ExceptionHandler({RestClientException.class, McpError.class, ToolExecutionException.class,
            ToolCallLimitExceededException.class, OAuth2AuthorizationException.class})
    ProblemDetail handleUpstreamFailure(RuntimeException exception) {
        log.error("Upstream failure serving a chat request", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, FAILED_DETAIL);
    }

    private static ProblemDetail badRequest(String detail) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isEmpty() ? VALIDATION_FALLBACK : detail);
    }
}
