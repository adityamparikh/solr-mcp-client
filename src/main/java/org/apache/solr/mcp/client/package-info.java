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
 * A REST service that puts a chat model in front of an Apache Solr MCP server.
 *
 * <p>This application is an MCP <em>client</em>: the Solr MCP server is a separate program that owns
 * the Solr connection and publishes the tools. Nothing here talks to Solr directly, and no Solr
 * client library is on the classpath — a question about a collection is answered by the model
 * calling a tool on that server.
 *
 * <p>Three packages divide the work, and the boundary between them is the direction of travel:
 *
 * <ul>
 *   <li>{@link org.apache.solr.mcp.client.assistant} — the assistant itself, stated without a
 *       transport. The chat client, its system prompt, conversation memory and the attachment of
 *       MCP tools live here.</li>
 *   <li>{@link org.apache.solr.mcp.client.mcp} — reaching the Solr MCP server: the outbound
 *       transport selected by profile, its service token, and the startup check that a usable
 *       connection exists.</li>
 *   <li>{@link org.apache.solr.mcp.client.web} — the REST service this application <em>is</em>:
 *       versioned controller, RFC 9457 error mapping, inbound security posture, OpenAPI.</li>
 * </ul>
 *
 * <p>Both {@code mcp} and {@code web} may speak HTTP; they are told apart by which way the traffic
 * flows. The test for placement: if the REST facade were replaced by an in-process UI, a class that
 * would still be needed does not belong in {@code web}.
 *
 * <p>A new capability gets its own package with its own {@code @Configuration}. There is no shared
 * {@code config} or {@code service} package, and each package keeps a {@code package-info.java}
 * stating its role.
 */
@NullMarked
package org.apache.solr.mcp.client;

import org.jspecify.annotations.NullMarked;
