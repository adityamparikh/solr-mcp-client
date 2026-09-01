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
package org.apache.solr.mcp.client.cli;

import org.apache.solr.mcp.client.mcp.McpToolVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that adding the Spring Shell starter changed nothing for a launch without the
 * {@code cli} profile: the shell's runner is replaced by this application's no-op and the context
 * is still a web application. The context is shaped exactly like
 * {@code McpStdioTransportWiringTest} so that what is asserted is the default launch, not a
 * test-specific variant.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.anthropic.api-key=test-key",
        "SOLR_MCP_JAR=/opt/solr-mcp/solr-mcp.jar",
        "SOLR_URL=http://solr.example.com:8983/solr/"
})
@ActiveProfiles("mcp-stdio")
class ShellSuppressionTest {

    // Same replacement as the transport wiring tests: listing the server's tools is what opens the
    // connection this test exists to avoid.
    @MockitoBean
    McpToolVerifier toolVerifier;

    @Autowired
    ApplicationContext context;

    @Test
    void replacesTheShellRunnerOutsideTheCliProfile() {
        // Spring Shell has no off switch; the auto-configured runner backs off only to a bean of
        // the same name. The bean present here must therefore be this application's no-op, not the
        // framework's REPL — which would otherwise be reading this JVM's stdin.
        Object runner = context.getBean("springShellApplicationRunner");

        assertThat(runner.getClass().getName()).startsWith("org.apache.solr.mcp.client.cli");
    }

    @Test
    void keepsTheWebApplication() {
        assertThat(context).isInstanceOf(WebApplicationContext.class);
    }
}
