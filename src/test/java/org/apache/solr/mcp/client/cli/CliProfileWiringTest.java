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
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.jline.JLineShellRunner;
import org.springframework.shell.jline.PromptProvider;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code cli,mcp-stdio} composition wires a REPL over the same assistant, without
 * starting one: a {@code @SpringBootTest} executes {@code ApplicationRunner} beans, and under the
 * {@code cli} profile the real Spring Shell runner would start reading this JVM's console — so it
 * is replaced by name. {@code spring.shell.context.close} is pinned back to {@code false} because
 * {@code application-cli.yml} turns it on, and its {@code ApplicationReadyEvent} listener would
 * close this test context the moment it finished starting.
 *
 * <p>{@code webEnvironment = NONE} is the test harness's own setting, declared because the default
 * {@code MOCK} imposes a servlet environment on the context regardless of what the profile says.
 * What belongs to the profile — and is asserted — is the {@code spring.main.web-application-type}
 * property that makes the real application non-web.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.anthropic.api-key=test-key",
        "SOLR_MCP_JAR=/opt/solr-mcp/solr-mcp.jar",
        "SOLR_URL=http://solr.example.com:8983/solr/",
        "spring.shell.context.close=false"
})
@ActiveProfiles({"cli", "mcp-stdio"})
class CliProfileWiringTest {

    // Same replacement as the transport wiring tests: listing the server's tools is what opens the
    // connection this test exists to avoid.
    @MockitoBean
    McpToolVerifier toolVerifier;

    // Replaced by name so the auto-configured runner never executes; the mock does nothing when
    // the test framework invokes it.
    @MockitoBean(name = "springShellApplicationRunner")
    ApplicationRunner springShellApplicationRunner;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void turnsTheWebServerOff() {
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    }

    @Test
    void silencesTheConsoleAppender() {
        // The REPL owns the terminal; a console appender writing to the same stream corrupts the
        // prompt whenever anything logs — a recurring WARN (e.g. a failing OTLP export) makes the
        // shell unusable. Same posture as the mcp-stdio profiles, same OFF-threshold mechanism.
        // Asserted against the cli document itself rather than the merged environment: mcp-stdio
        // is active here too and also turns the console off, which would mask a cli profile that
        // forgot to — the guarantee must hold just as much for the cli,mcp-http composition.
        var cliDocument = ((ConfigurableEnvironment) environment).getPropertySources().stream()
                .filter(source -> source.getName().contains("application-cli.yml"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("application-cli.yml should be loaded"));

        assertThat(cliDocument.getProperty("logging.threshold.console")).isEqualTo("OFF");
        // With the console silenced the failing export would just retry invisibly every publish
        // interval, so the metrics push is switched off at the source as well.
        assertThat(cliDocument.getProperty("management.otlp.metrics.export.enabled")).isEqualTo(false);
    }

    @Test
    void wiresTheJLineInteractiveRunner() {
        // The interactive and non-interactive runners are mutually exclusive on
        // spring.shell.interactive.enabled; the interactive one must be the survivor, since
        // `false` would silently mean arguments-as-command mode rather than a REPL. It must also
        // be the JLINE runner, not the plain-Console SystemShellRunner: java.io.Console reads EOF
        // and exits under any non-PTY stdin (IDE run consoles), where JLine falls back to a dumb
        // terminal instead — the reason the -ffm starter flavor is on the classpath.
        assertThat(context.getBean("jlineShellRunner")).isInstanceOf(JLineShellRunner.class);
    }

    @Test
    void usesTheDollarPrompt() {
        // PromptConfiguration must win over the auto-configured shell:> default, which backs off
        // via @ConditionalOnMissingBean(PromptProvider.class).
        assertThat(context.getBean(PromptProvider.class).getPrompt().toString()).isEqualTo("$ ");
    }

    @Test
    void registersTheAssistantCommands() {
        CommandRegistry registry = context.getBean(CommandRegistry.class);

        assertThat(registry.getCommandByName("solr-mcp")).isNotNull();
        assertThat(registry.getCommandByName("new")).isNotNull();
    }

    @Test
    void composesWithTheStdioTransportUnchanged() {
        // The cli profile selects the inbound adapter only; the outbound wiring must be exactly
        // what mcp-stdio alone produces (same assertion as McpStdioTransportWiringTest).
        var parameters = requireNonNull(
                context.getBean(McpStdioClientProperties.class).toServerParameters().get("solr-mcp"),
                "the mcp-stdio profile should configure a 'solr-mcp' server");

        assertThat(parameters.getCommand()).isEqualTo("java");
        assertThat(parameters.getArgs()).containsExactly("-jar", "/opt/solr-mcp/solr-mcp.jar");
    }
}
