package org.apache.solr.mcp.client.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpConnectionVerifierTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpConnectionVerifier.class);

    @Test
    void failsFastWhenNoSolrMcpConnectionIsConfigured() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Solr MCP connection is configured"));
    }

    @Test
    void startsWhenAConnectionIsPresent() {
        runner.withUserConfiguration(OneConnection.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(McpConnectionVerifier.class).connectionCount()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OneConnection {
        @Bean
        List<McpSyncClient> mcpSyncClients() {
            return List.of(mock(McpSyncClient.class));
        }
    }
}
