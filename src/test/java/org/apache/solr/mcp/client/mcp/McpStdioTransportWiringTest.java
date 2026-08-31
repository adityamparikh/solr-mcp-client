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
 * <p>The environment assertions are deliberate. {@code SOLR_URL} has to be declared because the SDK
 * hands the child only an allowlisted environment, so a value exported in the operator's shell never
 * reaches it. No profile is declared: the server already defaults to {@code stdio}, so naming it
 * would add a second server-owned key for no behaviour. An earlier revision passed {@code PROFILES},
 * which Spring itself ignores — the fix is to pass neither.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.anthropic.api-key=test-key",
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
    void passesTheSolrCoordinatesTheChildCannotInherit() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getEnv())
                .containsEntry("SOLR_URL", "http://solr.example.com:8983/solr/");
    }

    @Test
    void leavesTheServerProfileToTheServersOwnDefault() {
        var parameters = stdioProperties.toServerParameters().get("solr-mcp");

        assertThat(parameters.getEnv())
                .doesNotContainKey("SPRING_PROFILES_ACTIVE")
                .doesNotContainKey("PROFILES");
    }
}
