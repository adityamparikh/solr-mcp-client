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
 * The REST service this application <em>is</em>.
 *
 * <p>Everything here concerns HTTP that this application <strong>serves</strong>: the versioned
 * controller, RFC 9457 error mapping, the inbound security posture, and the OpenAPI description.
 * The application is a REST service in every profile except {@code cli}, which replaces the web
 * server with the interactive shell in {@link org.apache.solr.mcp.client.cli}; the {@code mcp-*}
 * profiles choose how it reaches Solr MCP, never whether it has a web layer.
 *
 * <p>Nothing about the <em>outbound</em> connection to Solr MCP belongs here, even when that
 * connection also happens to speak HTTP; that lives in
 * {@link org.apache.solr.mcp.client.mcp}. The distinguishing question is which direction the
 * traffic flows: if replacing this REST facade with an in-process UI would leave a class still
 * needed, the class does not belong in this package.
 *
 * <p>This package is an adapter over {@link org.apache.solr.mcp.client.assistant.SolrAssistant} and
 * holds no business logic.
 */
@NullMarked
package org.apache.solr.mcp.client.web;

import org.jspecify.annotations.NullMarked;
