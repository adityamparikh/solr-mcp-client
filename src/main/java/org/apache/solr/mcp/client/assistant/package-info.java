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
 * The Solr assistant, expressed independently of any transport.
 *
 * <p>{@link org.apache.solr.mcp.client.assistant.SolrAssistant} is the seam a user interface binds
 * to. {@link org.apache.solr.mcp.client.web} is one adapter over it; an in-process UI (Vaadin,
 * Hilla, a CLI runner) injects the bean directly rather than calling this application's own HTTP
 * endpoints. Chat client wiring, memory scoping and MCP tool attachment live here so that adding a
 * UI duplicates none of it.
 *
 * <p>Only {@code SolrAssistant} is public; the chat client and its configuration are package
 * private. New assistant behaviour belongs here, not in a controller.
 */
package org.apache.solr.mcp.client.assistant;
