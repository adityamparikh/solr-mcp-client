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
package org.apache.solr.mcp.client.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Fails startup unless the Solr MCP server actually offers tools to call.
 *
 * <p>Listing the tools answers every question worth asking at startup in one step. An empty list
 * means either that no MCP connection is configured at all, or that the connected process is not
 * the Apache Solr MCP server — a stale {@code SOLR_MCP_JAR}, or a {@code SOLR_MCP_HTTP_URL} pointing
 * somewhere else. Both otherwise produce a healthy-looking application that fails only as unhelpful
 * model answers, because the assistant has nothing to call.
 *
 * <p>The check stops at "is the list non-empty" and deliberately does not assert that particular
 * tools are present. Tool names carry a per-connection prefix from Spring AI's
 * {@code McpToolNamePrefixGenerator}, so a list of expected names would pin this client to a naming
 * scheme neither it nor the server owns, and would have to be revised whenever the server adds or
 * renames a tool. What an operator actually needs is the names the server exposes, which is why the
 * successful path logs them.
 *
 * <p>Verification is unconditional. There is no configuration that turns it off, because an
 * assistant with nothing to call is never a state worth starting in. One consequence is worth
 * knowing before changing anything here: listing the tools is what first drives Spring AI's
 * {@code LifecycleInitializer}, so this class connects even when {@code
 * spring.ai.mcp.client.initialized} is false — that property defers the connection, and verifying
 * necessarily undoes the deferral. A context that must not reach a server therefore has to replace
 * this bean rather than set that property; the transport wiring tests use {@code @MockitoBean} for
 * exactly that.
 */
@Component
class McpToolVerifier {

    private static final Logger log = LoggerFactory.getLogger(McpToolVerifier.class);

    private final ObjectProvider<ToolCallbackProvider> toolCallbacks;

    /**
     * The provider is injected as an {@link ObjectProvider} because "no
     * {@code ToolCallbackProvider} bean at all" is one of the conditions this class exists to
     * report; a hard dependency would turn it into an opaque context-startup failure instead.
     */
    McpToolVerifier(ObjectProvider<ToolCallbackProvider> toolCallbacks) {
        this.toolCallbacks = toolCallbacks;
    }

    /**
     * Runs once this bean's own dependencies are ready; throwing here aborts context startup,
     * which is the intended failure mode — a broken MCP connection must never become a running
     * application. On success the exposed tool names are logged, since those prefixed names are
     * what an operator needs and nothing else reports them.
     */
    @PostConstruct
    void verifyToolsAvailable() {
        SortedSet<String> available = availableToolNames();
        if (available.isEmpty()) {
            throw new IllegalStateException("""
                    The Solr MCP server offers no tools, so the assistant has nothing to call. \
                    Either no MCP connection is configured — activate the 'mcp-stdio' profile (the \
                    default), 'mcp-stdio-docker' or 'mcp-http' — or the connected process is not \
                    the Apache Solr MCP server.""");
        }

        log.info("Solr MCP server exposes {} tools: {}", available.size(), available);
    }

    /**
     * Sorted so the logged list and any failure report read stably across restarts. A missing
     * provider bean collapses into the same empty result as a toolless server, because the two
     * conditions warrant the same diagnosis.
     */
    private SortedSet<String> availableToolNames() {
        ToolCallbackProvider provider = toolCallbacks.getIfAvailable();
        if (provider == null) {
            return new TreeSet<>();
        }
        return Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
