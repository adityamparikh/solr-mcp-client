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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import org.jspecify.annotations.Nullable;

/**
 * Writes the trace context of the call that triggered an MCP request into that request's headers,
 * so the span the server starts joins the caller's trace instead of beginning a new one.
 *
 * <p>This exists because the MCP SDK's Streamable HTTP transport builds requests with its own raw
 * JDK {@code HttpClient} — one of the few clients Spring Boot's tracing does not instrument, so
 * nothing else ever adds the propagation headers. The header format (W3C {@code traceparent} by
 * default) is the auto-configured {@link Propagator}'s decision, not this class's: whatever
 * propagation Spring Boot is configured with is what crosses the wire, and the server continues it
 * with no changes of its own.
 *
 * <p>The trace context is read from the {@link McpTransportContext} under
 * {@link #TRACE_CONTEXT_KEY} first, and only then from the thread-local current span. The order is
 * load-bearing: the SDK invokes this customizer inside its Reactor pipeline — usually on a
 * transport worker thread where no span is current — so the caller's context has to be captured at
 * call time and carried here. The transport-context configuration in the {@code mcp} package does
 * that capturing; the thread-local read is only a fallback for the case where the SDK happens to
 * build the request on an instrumented thread.
 *
 * <p>When neither source has a span — client startup verification, or any call outside an
 * observation — the request is left untouched and the server starts its own trace, which is
 * exactly the behaviour before this class existed.
 */
public class TracePropagatingHttpRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

    /**
     * Key under which the capture side stores the caller's {@link TraceContext} in the
     * {@link McpTransportContext}. Namespaced by class name because the transport context is
     * shared with other customizers — mcp-client-security stores its authentication there too.
     */
    public static final String TRACE_CONTEXT_KEY =
            TracePropagatingHttpRequestCustomizer.class.getName() + ".traceContext";

    private final Tracer tracer;

    private final Propagator propagator;

    public TracePropagatingHttpRequestCustomizer(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public void customize(HttpRequest.Builder builder, String method, URI endpoint, @Nullable String body,
            McpTransportContext context) {
        TraceContext traceContext = traceContext(context);
        if (traceContext != null) {
            // A lambda rather than HttpRequest.Builder::header because Propagator.Setter declares
            // its carrier @Nullable; the carrier here is always the builder passed above.
            propagator.inject(traceContext, builder, (carrier, key, value) -> {
                if (carrier != null) {
                    carrier.header(key, value);
                }
            });
        }
    }

    private @Nullable TraceContext traceContext(McpTransportContext context) {
        if (context.get(TRACE_CONTEXT_KEY) instanceof TraceContext captured) {
            return captured;
        }
        Span span = tracer.currentSpan();
        return span != null ? span.context() : null;
    }

}
