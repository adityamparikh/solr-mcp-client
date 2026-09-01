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
 * Closing the gap in Spring Boot's OpenTelemetry support that this application runs into: trace
 * context across MCP calls. The MCP SDK's HTTP transport issues requests through its own raw JDK
 * {@code HttpClient}, which Spring Boot does not instrument, so an outbound tool call would
 * silently start a fresh trace on the server.
 * {@link org.apache.solr.mcp.client.observability.TracePropagatingHttpRequestCustomizer} injects
 * the current trace context into those requests; the {@code mcp-http} transport configuration
 * composes it into the transport's single customizer slot.
 *
 * <p>Metrics need no help here: they leave through Micrometer's OTLP registry, which speaks only
 * OTLP over HTTP — a deliberate trade. A gRPC-only collector (IntelliJ IDEA's built-in receiver is
 * one) gets traces and logs but no metrics; point an OTLP/HTTP collector at the application to
 * receive all three.
 */
@NullMarked
package org.apache.solr.mcp.client.observability;

import org.jspecify.annotations.NullMarked;
