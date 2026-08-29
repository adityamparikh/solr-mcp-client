package org.apache.solr.mcp.client.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

class McpToolVerifierTest {

    private static final ToolCallbackProvider SOLR_TOOLS =
            provider("solr_search", "solr_index_document");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpToolVerifier.class);

    @Test
    void startsWhenEveryExpectedToolIsExposed() {
        runner.withBean(ToolCallbackProvider.class, () -> SOLR_TOOLS)
                .withPropertyValues("solr.mcp.client.expected-tools=solr_search,solr_index_document")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsFastNamingTheMissingToolsAndTheOnesActuallyExposed() {
        runner.withBean(ToolCallbackProvider.class, () -> SOLR_TOOLS)
                .withPropertyValues("solr.mcp.client.expected-tools=solr_search,solr_delete_collection")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        // The operator needs both halves: what is missing, and what is on offer.
                        .hasMessageContaining("solr_delete_collection")
                        .hasMessageContaining("solr_search"));
    }

    @Test
    void failsWhenTheServerExposesNoToolsAtAll() {
        runner.withBean(ToolCallbackProvider.class, () -> provider())
                .withPropertyValues("solr.mcp.client.expected-tools=solr_search")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    void neverContactsTheServerWhenNoToolsAreExpected() {
        ToolCallbackProvider untouched = mock(ToolCallbackProvider.class);

        runner.withBean(ToolCallbackProvider.class, () -> untouched).run(context -> {
            assertThat(context).hasNotFailed();
            // Listing tools requires talking to the server; an application with no expectation
            // must not pay for that at startup.
            then(untouched).shouldHaveNoInteractions();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class NoProvider {
        @Bean
        String placeholder() {
            return "";
        }
    }

    @Test
    void failsWhenNoToolProviderExistsAtAll() {
        runner.withUserConfiguration(NoProvider.class)
                .withPropertyValues("solr.mcp.client.expected-tools=solr_search")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class));
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
