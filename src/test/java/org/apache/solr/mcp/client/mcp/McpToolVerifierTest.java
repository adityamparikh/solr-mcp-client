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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class McpToolVerifierTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpToolVerifier.class);

    @Test
    void startsWhenTheServerOffersTools() {
        runner.withBean(ToolCallbackProvider.class, () -> provider("solr_search"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsWhenTheServerOffersNoTools() {
        // Covers both "no connection configured" and "connected to the wrong server": either way
        // the assistant has nothing to call.
        runner.withBean(ToolCallbackProvider.class, () -> provider())
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("offers no tools")
                        .hasMessageContaining("mcp-stdio"));
    }

    @Test
    void failsWhenThereIsNoToolProviderAtAll() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offers no tools"));
    }

    @Test
    void staysQuietWhenTheClientsWereToldNotToInitialize() {
        ToolCallbackProvider untouched = mock(ToolCallbackProvider.class);

        // Listing tools requires a live connection. With initialization disabled there is nothing
        // to list, so silence proves nothing and must not be read as failure.
        runner.withBean(ToolCallbackProvider.class, () -> untouched)
                .withPropertyValues("spring.ai.mcp.client.initialized=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    then(untouched).shouldHaveNoInteractions();
                });
    }

    private static ToolCallbackProvider provider(String... names) {
        ToolCallback[] callbacks = new ToolCallback[names.length];
        for (int i = 0; i < names.length; i++) {
            ToolDefinition definition = mock(ToolDefinition.class);
            given(definition.name()).willReturn(names[i]);
            ToolCallback callback = mock(ToolCallback.class);
            given(callback.getToolDefinition()).willReturn(definition);
            callbacks[i] = callback;
        }
        return ToolCallbackProvider.from(callbacks);
    }
}
