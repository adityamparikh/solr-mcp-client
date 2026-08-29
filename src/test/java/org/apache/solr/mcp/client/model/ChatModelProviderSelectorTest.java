package org.apache.solr.mcp.client.model;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.apache.solr.mcp.client.model.ChatModelProviderSelector.NO_PROVIDER;
import static org.apache.solr.mcp.client.model.ChatModelProviderSelector.PROVIDER_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ChatModelProviderSelectorTest {

    private final ChatModelProviderSelector selector = new ChatModelProviderSelector();

    @Test
    void selectsOpenAiFromItsEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment().withProperty("OPENAI_API_KEY", "sk-openai");

        selector.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo("openai");
        assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-openai");
    }

    @Test
    void selectsAnthropicFromItsEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment().withProperty("ANTHROPIC_API_KEY", "sk-anthropic");

        selector.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo("anthropic");
        assertThat(environment.getProperty("spring.ai.anthropic.api-key")).isEqualTo("sk-anthropic");
    }

    @Test
    void refusesToGuessWhenSeveralProvidersAreConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("OPENAI_API_KEY", "sk-openai")
                .withProperty("ANTHROPIC_API_KEY", "sk-anthropic");

        // Picking a winner would silently decide which account is billed and how the assistant
        // behaves, and neither is visible from outside.
        assertThatIllegalStateException()
                .isThrownBy(() -> selector.postProcessEnvironment(environment, null))
                .withMessageContaining("anthropic")
                .withMessageContaining("openai")
                .withMessageContaining(PROVIDER_PROPERTY);
    }

    @Test
    void leavesADeliberateChoiceAlone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PROVIDER_PROPERTY, "anthropic")
                .withProperty("OPENAI_API_KEY", "sk-openai")
                .withProperty("ANTHROPIC_API_KEY", "sk-anthropic");

        // An explicit choice is also how an operator resolves the ambiguity above, so it must win
        // rather than trip the same failure.
        selector.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo("anthropic");
    }

    @Test
    void activatesNoProviderWhenNoKeyIsPresent() {
        MockEnvironment environment = new MockEnvironment();

        selector.postProcessEnvironment(environment, null);

        // Failing here would break slices that never touch a model; the report belongs where a
        // chat model is actually required.
        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo(NO_PROVIDER);
    }

    @Test
    void neverOverwritesAnExplicitlyConfiguredApiKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "configured-key")
                .withProperty("OPENAI_API_KEY", "environment-key");

        // Otherwise a test that pins a dummy key would silently be handed the developer's real one.
        selector.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("configured-key");
        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo("openai");
    }

    @Test
    void recognisesAProviderConfiguredThroughSpringPropertiesAlone() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.ai.anthropic.api-key", "sk-anthropic");

        selector.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(PROVIDER_PROPERTY)).isEqualTo("anthropic");
    }
}
