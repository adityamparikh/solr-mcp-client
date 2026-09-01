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
package org.apache.solr.mcp.client.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

/**
 * The customizer's contract has two halves: with a current span it hands the request builder to
 * the propagator (whose header format is its own business), and without one it must leave the
 * request completely untouched — a half-written or invented context would be worse than none.
 */
class TracePropagatingHttpRequestCustomizerTest {

    private static final URI ENDPOINT = URI.create("http://localhost:8080/mcp");

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private final Tracer tracer = mock(Tracer.class);

    private final Propagator propagator = mock(Propagator.class);

    private final TracePropagatingHttpRequestCustomizer customizer =
            new TracePropagatingHttpRequestCustomizer(tracer, propagator);

    private final HttpRequest.Builder builder = HttpRequest.newBuilder(ENDPOINT);

    @Test
    void currentSpanIsInjectedIntoTheRequest() {
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        doAnswer(invocation -> {
            HttpRequest.Builder carrier = invocation.getArgument(1);
            Propagator.Setter<HttpRequest.Builder> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", TRACEPARENT);
            return null;
        }).when(propagator).inject(eq(traceContext), any(), any());

        customizer.customize(builder, "POST", ENDPOINT, "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue("traceparent")).contains(TRACEPARENT);
    }

    /**
     * The production path: the SDK invokes the customizer on a transport thread with no span
     * thread-local, and the caller's context arrives captured inside the transport context.
     */
    @Test
    void contextCarriedTraceContextIsInjectedWithoutACurrentSpan() {
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(null);
        doAnswer(invocation -> {
            Propagator.Setter<HttpRequest.Builder> setter = invocation.getArgument(2);
            setter.set(invocation.getArgument(1), "traceparent", TRACEPARENT);
            return null;
        }).when(propagator).inject(eq(traceContext), any(), any());
        McpTransportContext context = McpTransportContext.create(
                java.util.Map.of(TracePropagatingHttpRequestCustomizer.TRACE_CONTEXT_KEY, traceContext));

        customizer.customize(builder, "POST", ENDPOINT, "{}", context);

        assertThat(builder.build().headers().firstValue("traceparent")).contains(TRACEPARENT);
    }

    @Test
    void noCurrentSpanLeavesTheRequestUntouched() {
        when(tracer.currentSpan()).thenReturn(null);

        customizer.customize(builder, "POST", ENDPOINT, "{}", McpTransportContext.EMPTY);

        verifyNoInteractions(propagator);
        assertThat(builder.build().headers().map()).doesNotContainKey("traceparent");
    }

}
