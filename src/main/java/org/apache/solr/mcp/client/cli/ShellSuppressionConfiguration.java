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

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Keeps every launch outside the {@code cli} profile REPL-free.
 *
 * <p>Spring Shell 4 offers no property that disables the shell:
 * {@code spring.shell.interactive.enabled=false} switches to non-interactive
 * (arguments-as-command) mode rather than off, and the runner that starts either mode —
 * {@code ShellRunnerAutoConfiguration}'s {@code ApplicationRunner} named
 * {@code springShellApplicationRunner} — is guarded only by {@code @ConditionalOnMissingBean}.
 * Supplying a bean of that name is therefore the framework's own mechanism for standing the shell
 * down, and this no-op is exactly that. Without it, the web application would also grow a REPL
 * reading its stdin.
 *
 * <p>The bean name must match the auto-configured one exactly. Note the flip side of the
 * mechanism: <em>any</em> {@code ApplicationRunner} bean backs the auto-configuration off, so a
 * runner added elsewhere for an unrelated startup task would silently disable the {@code cli}
 * shell too — if one is ever needed, register it as an {@code ApplicationListener} or
 * {@code SmartInitializingSingleton} instead.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!cli")
class ShellSuppressionConfiguration {

    /**
     * The stand-in for Spring Shell's runner: same name, does nothing.
     */
    @Bean
    ApplicationRunner springShellApplicationRunner() {
        return args -> {
        };
    }
}
