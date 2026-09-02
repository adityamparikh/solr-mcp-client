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
 * Reaching the Solr MCP server — this application acting as a client.
 *
 * <p>Three profiles select the transport, and every name is prefixed {@code mcp-} because they
 * describe the <em>outbound</em> connection, never how this application serves its own API:
 *
 * <ul>
 *   <li>{@code mcp-stdio} (default) launches the Solr MCP server as a child process from a jar.
 *       Here this application is also the server's launcher and therefore owns the child's
 *       environment.</li>
 *   <li>{@code mcp-stdio-docker} is the same transport with a container as the child. It is a
 *       separate profile rather than an overridable command line because the MCP SDK applies its
 *       {@code env:} map to the launched process — which here is the {@code docker} CLI, not the
 *       container — so container settings have to travel as {@code -e} flags, and because
 *       {@code SOLR_URL} must name {@code host.docker.internal} rather than {@code localhost}.</li>
 *   <li>{@code mcp-http} connects to an independently deployed server over Streamable HTTP,
 *       authenticated with a dedicated OAuth2 client-credentials service token.</li>
 * </ul>
 *
 * <p>Only {@code mcp-http} needs code here; the two stdio profiles are configuration alone. Keep
 * the two stdio profiles in step when either changes.
 *
 * <p>A single startup check guards the dependency: the server's tool list must be non-empty, which
 * answers both "is a connection configured" and "is this the right server" at once.
 *
 * <p>The inbound adapters are {@link org.apache.solr.mcp.client.web} (the REST facade) and
 * {@link org.apache.solr.mcp.client.cli} (the shell). These packages are independent: swapping the
 * transport changes nothing inbound, and the {@code mcp-*} profiles compose with the {@code cli}
 * profile unchanged — {@code cli,mcp-http} is the same outbound wiring as {@code mcp-http} alone.
 */
@NullMarked
package org.apache.solr.mcp.client.mcp;

import org.jspecify.annotations.NullMarked;
