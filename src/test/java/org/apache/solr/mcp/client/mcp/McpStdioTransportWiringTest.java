package org.apache.solr.mcp.client.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the default {@code mcp-stdio} wiring without launching the Solr MCP server: the client is
 * left uninitialized, so the transport binds but never spawns the child process.
 *
 * <p>The environment assertion is deliberate — the child is a Spring Boot application and reads
 * {@code SPRING_PROFILES_ACTIVE}; an earlier revision passed {@code PROFILES}, which that process
 * silently ignores.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.openai.api-key=test-key",
        "SOLR_MCP_JAR=/opt/solr-mcp/solr-mcp.jar",
        "SOLR_URL=http://solr.example.com:8983/solr/"
})
@ActiveProfiles("mcp-stdio")
class McpStdioTransportWiringTest {

    @Autowired
    McpStdioClientProperties stdioProperties;

    @Autowired
    Environment environment;

    @Test
    void isTheProfileThatAppliesWhenNoneIsRequested() {
        assertThat(environment.getProperty("spring.profiles.default")).isEqualTo("mcp-stdio");
    }

    @Test
    void launchesTheSolrMcpServerFromAnExplicitJarLocation() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getCommand()).isEqualTo("java");
        assertThat(parameters.getArgs()).containsExactly("-jar", "/opt/solr-mcp/solr-mcp.jar");
    }

    @Test
    void passesSolrCoordinatesAndTheProfileTheChildProcessActuallyReads() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getEnv())
                .containsEntry("SOLR_URL", "http://solr.example.com:8983/solr/")
                .containsEntry("SPRING_PROFILES_ACTIVE", "stdio")
                .doesNotContainKey("PROFILES");
    }
}
