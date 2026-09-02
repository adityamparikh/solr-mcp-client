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
 * The interactive command-line adapter over the Solr assistant.
 *
 * <p>Like {@link org.apache.solr.mcp.client.web}, this package is an adapter over
 * {@link org.apache.solr.mcp.client.assistant.SolrAssistant} and holds no business logic. It
 * injects the bean directly rather than calling this application's own HTTP endpoints, and it maps
 * the shell's input onto the assistant's {@code ChatRequest}/{@code ChatReply} vocabulary rather
 * than defining a parallel pair of its own.
 *
 * <p>The {@code cli} profile is the one profile that selects the inbound adapter: it replaces the
 * web server with a Spring Shell REPL ({@code spring.main.web-application-type=none} in
 * {@code application-cli.yml}). It says nothing about the outbound side, so it must be composed
 * with exactly one {@code mcp-*} transport profile — {@code cli,mcp-stdio},
 * {@code cli,mcp-stdio-docker} or {@code cli,mcp-http}. Activating {@code cli} alone would also
 * drop the {@code mcp-stdio} default, because {@code spring.profiles.default} is replaced by an
 * explicit activation, never merged with it.
 *
 * <p>Outside the {@code cli} profile this package's job inverts: Spring Shell has no property that
 * turns the shell off, so {@code ShellSuppressionConfiguration} overrides the auto-configured
 * runner to keep every other launch REPL-free.
 */
@NullMarked
package org.apache.solr.mcp.client.cli;

import org.jspecify.annotations.NullMarked;
