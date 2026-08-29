package org.apache.solr.mcp.client.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Set;
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
 * <p>{@code solr.mcp.client.expected-tools} narrows this further, naming tools this deployment
 * depends on. Tool names may carry a per-connection prefix from Spring AI's
 * {@code McpToolNamePrefixGenerator}, so a failure reports the names the server actually exposes
 * rather than leaving an operator to guess the prefixed form.
 *
 * <p>Verification is skipped when {@code spring.ai.mcp.client.initialized} is false: the clients
 * have been told not to connect, so there is nothing to list and nothing to conclude from silence.
 */
@Component
class McpToolVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(McpToolVerifier.class);

    private final ObjectProvider<ToolCallbackProvider> toolCallbacks;
    private final Set<String> expectedTools;
    private final boolean clientsInitialized;

    // expected-tools is split explicitly rather than bound straight to a collection: @Value
    // collection conversion depends on Boot's ApplicationConversionService being present, so it
    // silently yields a single element in contexts that lack it.
    McpToolVerifier(ObjectProvider<ToolCallbackProvider> toolCallbacks,
                    @Value("${solr.mcp.client.expected-tools:}") String expectedTools,
                    @Value("${spring.ai.mcp.client.initialized:true}") boolean clientsInitialized) {
        this.toolCallbacks = toolCallbacks;
        this.expectedTools = StringUtils.commaDelimitedListToSet(expectedTools);
        this.clientsInitialized = clientsInitialized;
    }

    @Override
    public void afterPropertiesSet() {
        if (!clientsInitialized) {
            log.info("MCP clients are not initialized; skipping Solr MCP tool verification");
            return;
        }

        SortedSet<String> available = availableToolNames();
        if (available.isEmpty()) {
            throw new IllegalStateException("""
                    The Solr MCP server offers no tools, so the assistant has nothing to call. \
                    Either no MCP connection is configured — activate the 'mcp-stdio' profile (the \
                    default) or 'mcp-http' — or the connected process is not the Apache Solr MCP \
                    server.""");
        }

        SortedSet<String> missing = new TreeSet<>(expectedTools);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("""
                    The connected MCP server does not expose the expected tools %s. It exposes: %s. \
                    Check that this client is pointed at the Apache Solr MCP server, and note that \
                    tool names may carry a per-connection prefix — configure \
                    solr.mcp.client.expected-tools with the names listed here."""
                    .formatted(missing, available));
        }

        log.info("Solr MCP server exposes {} tools: {}", available.size(), available);
    }

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
