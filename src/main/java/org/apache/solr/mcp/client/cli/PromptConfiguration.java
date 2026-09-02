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

import org.jline.utils.AttributedString;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.shell.jline.PromptProvider;

/**
 * Replaces Spring Shell's default {@code shell:>} prompt with a plain {@code $ }.
 *
 * <p>A {@link PromptProvider} bean is the framework's only customization point — Spring Shell 4
 * exposes no prompt property. {@code JLineShellAutoConfiguration}'s default provider is guarded by
 * {@code @ConditionalOnMissingBean(PromptProvider.class)}, so this bean simply takes its place.
 *
 * <p>Guarded by the {@code cli} profile like the rest of this package: outside it the shell never
 * runs, and the bean would only pin spring-shell-jline classes into contexts that never use them.
 */
@Configuration(proxyBeanMethods = false)
@Profile("cli")
class PromptConfiguration {

    @Bean
    PromptProvider promptProvider() {
        return () -> new AttributedString("$ ");
    }
}
