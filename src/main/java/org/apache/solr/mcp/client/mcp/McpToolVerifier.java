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
 * Fails startup when the connected MCP server does not expose the tools this assistant needs.
 *
 * <p>{@link McpConnectionVerifier} proves a connection is <em>configured</em>; it cannot tell that
 * the process on the other end is the Solr MCP server. A stale {@code SOLR_MCP_JAR} or an
 * {@code SOLR_MCP_HTTP_URL} pointing at some other MCP server starts cleanly and then fails only as
 * unhelpful model answers, because the assistant simply has no Solr tools to call.
 *
 * <p>Verification is opt-in through {@code solr.mcp.client.expected-tools}: listing tools requires
 * talking to the server, and an application that has no opinion about which tools it needs should
 * not pay for that at startup. Tool names may carry a per-connection prefix from Spring AI's
 * {@code McpToolNamePrefixGenerator}, so the failure reports the names the server actually exposes
 * rather than leaving an operator to guess the prefixed form.
 */
@Component
class McpToolVerifier implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(McpToolVerifier.class);

    private final ObjectProvider<ToolCallbackProvider> toolCallbacks;
    private final Set<String> expectedTools;

    // Split explicitly rather than binding straight to a collection: @Value collection conversion
    // depends on Boot's ApplicationConversionService being present, so it silently yields a single
    // element in contexts that lack it.
    McpToolVerifier(ObjectProvider<ToolCallbackProvider> toolCallbacks,
                    @Value("${solr.mcp.client.expected-tools:}") String expectedTools) {
        this.toolCallbacks = toolCallbacks;
        this.expectedTools = StringUtils.commaDelimitedListToSet(expectedTools);
    }

    @Override
    public void afterPropertiesSet() {
        if (expectedTools.isEmpty()) {
            return;
        }
        SortedSet<String> available = availableToolNames();
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
        log.info("Solr MCP server exposes all {} expected tools", expectedTools.size());
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
