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
/**
 * Closing the two gaps in Spring Boot's OpenTelemetry support that this application runs into.
 *
 * <p><b>Metrics over gRPC.</b> {@code spring-boot-starter-opentelemetry} exports traces and logs
 * through the OpenTelemetry SDK, which can speak both OTLP transports, but keeps metrics on
 * Micrometer's OTLP registry, which speaks only OTLP over HTTP. A collector that accepts only gRPC
 * — IntelliJ IDEA's built-in OpenTelemetry receiver is one, and it announces itself by injecting
 * {@code OTEL_EXPORTER_OTLP_PROTOCOL=grpc} — would receive traces and logs but reject every
 * metrics POST. {@link org.apache.solr.mcp.client.observability.OtlpGrpcMetricsConfiguration}
 * reacts to that announced protocol by routing metrics through the SDK's gRPC exporter, and is
 * inert without it. The HTTP registry itself is excluded in {@code build.gradle.kts} — the
 * exclusion's comment explains why it can succeed nowhere this application runs.
 *
 * <p><b>Trace context across MCP calls.</b> The MCP SDK's HTTP transport issues requests through
 * its own raw JDK {@code HttpClient}, which Spring Boot does not instrument, so an outbound tool
 * call would silently start a fresh trace on the server.
 * {@link org.apache.solr.mcp.client.observability.TracePropagatingHttpRequestCustomizer} injects
 * the current trace context into those requests; the {@code mcp-http} transport configuration
 * composes it into the transport's single customizer slot.
 */
@NullMarked
package org.apache.solr.mcp.client.observability;

import org.jspecify.annotations.NullMarked;
