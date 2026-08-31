package org.apache.solr.mcp.client.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code mcp-stdio-docker} wiring without launching a container: the client is left
 * uninitialized, so the transport binds but never spawns {@code docker}.
 *
 * <p>The assertions pin the two things that separate this profile from {@code mcp-stdio} rather
 * than the whole argv for its own sake. {@code -i} keeps the container's stdin open, without which
 * the transport sees EOF before the first JSON-RPC message. Container settings must travel as
 * {@code -e} flags, because the {@code env} map the SDK understands is applied to the {@code docker}
 * CLI process and never reaches the container.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.openai.api-key=test-key",
        "SOLR_URL=http://solr.example.com:8983/solr/",
        "SOLR_MCP_IMAGE=solr-mcp:9.9.9"
})
@ActiveProfiles("mcp-stdio-docker")
class McpStdioDockerTransportWiringTest {

    @Autowired
    McpStdioClientProperties stdioProperties;

    @Test
    void launchesTheSolrMcpServerAsAContainerWithStdinHeldOpen() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getCommand()).isEqualTo("docker");
        assertThat(parameters.getArgs()).startsWith("run", "-i", "--rm");
        assertThat(parameters.getArgs()).last().isEqualTo("solr-mcp:9.9.9");
    }

    @Test
    void passesSolrCoordinatesAsAContainerEnvironmentFlag() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getArgs())
                .containsSequence("-e", "SOLR_URL=http://solr.example.com:8983/solr/");
    }

    /**
     * Unlike {@code mcp-stdio}, this profile passes no server profile: the server defaults to
     * {@code stdio} on its own and every published image bakes that in, so the flag would only add
     * a second server-owned key to keep in step.
     */
    @Test
    void leavesTheServerProfileToTheImageAndTheServersOwnDefault() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getArgs()).noneMatch(arg -> arg.contains("SPRING_PROFILES_ACTIVE"));
    }

    @Test
    void resolvesTheHostAliasSoSolrOnTheHostIsReachableFromInsideTheContainer() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getArgs()).contains("--add-host=host.docker.internal:host-gateway");
    }

    @Test
    void leavesTheEnvMapEmptyBecauseItWouldReachTheDockerCliRatherThanTheContainer() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getEnv())
                .doesNotContainKey("SOLR_URL")
                .doesNotContainKey("SPRING_PROFILES_ACTIVE");
    }
}
