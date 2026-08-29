package org.apache.solr.mcp.client.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fails startup when no Solr MCP connection is configured.
 *
 * <p>Transport configuration lives in profile-specific documents, so activating a profile other
 * than {@code mcp-stdio} or {@code mcp-http} would otherwise produce a healthy-looking application whose
 * assistant has no tools at all — a failure that only surfaces as unhelpful model answers.
 */
@Component
class McpConnectionVerifier implements InitializingBean {

    private final ObjectProvider<List<McpSyncClient>> mcpSyncClients;

    McpConnectionVerifier(ObjectProvider<List<McpSyncClient>> mcpSyncClients) {
        this.mcpSyncClients = mcpSyncClients;
    }

    @Override
    public void afterPropertiesSet() {
        if (connectionCount() == 0) {
            throw new IllegalStateException("""
                    No Solr MCP connection is configured. Activate the 'mcp-stdio' profile (the \
                    default) or the 'mcp-http' profile.""");
        }
    }

    int connectionCount() {
        return mcpSyncClients.getIfAvailable(List::of).size();
    }
}
