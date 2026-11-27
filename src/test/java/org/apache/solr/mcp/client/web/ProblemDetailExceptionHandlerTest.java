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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ProblemDetailExceptionHandlerTest {

    @Test
    void fallsBackToAGenericDetailWhenABodyViolationCarriesNoMessage() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        given(exception.getBindingResult()).willReturn(bindingResult);
        given(bindingResult.getFieldErrors()).willReturn(List.of());

        ProblemDetail problem = ProblemDetailExceptionHandler.invalidBody(exception);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Request validation failed");
    }

    @Test
    void fallsBackToAGenericDetailWhenAParameterViolationCarriesNoMessage() {
        HandlerMethodValidationException exception = mock(HandlerMethodValidationException.class);
        given(exception.getParameterValidationResults()).willReturn(List.of());

        ProblemDetail problem = ProblemDetailExceptionHandler.invalidParameter(exception);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Request validation failed");
    }

    @Test
    void joinsEachDistinctBodyViolationOnceAndSkipsMissingMessages() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        given(exception.getBindingResult()).willReturn(bindingResult);
        List<FieldError> errors = List.of(
                fieldError("message must not be blank"),
                fieldError(null),
                fieldError("message must not be blank"),
                fieldError("message is too long"));
        given(bindingResult.getFieldErrors()).willReturn(errors);

        ProblemDetail problem = ProblemDetailExceptionHandler.invalidBody(exception);

        assertThat(problem.getDetail()).isEqualTo("message must not be blank; message is too long");
        assertThat(problem.getTitle()).isEqualTo("Request Validation Failed");
        assertThat(problem.getProperties()).containsKey("timestamp");
    }

    private static FieldError fieldError(@Nullable String message) {
        FieldError error = mock(FieldError.class);
        given(error.getDefaultMessage()).willReturn(message);
        return error;
    }
}
