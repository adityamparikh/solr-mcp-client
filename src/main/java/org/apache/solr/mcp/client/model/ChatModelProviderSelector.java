package org.apache.solr.mcp.client.model;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chooses which chat model provider to activate from the API keys present in the environment.
 *
 * <p>Spring AI gates each provider on {@code spring.ai.model.chat}, and every provider's
 * auto-configuration declares {@code matchIfMissing = true}. With more than one provider starter on
 * the classpath and that property unset, they all publish a {@code ChatModel} and Spring AI's
 * {@code ChatClient.Builder} then fails on the ambiguity. Something has to settle it before those
 * conditions are evaluated, which is why this runs as an {@link EnvironmentPostProcessor} rather
 * than as a bean.
 *
 * <p>The rules, in order:
 *
 * <ul>
 *   <li>An explicit {@code spring.ai.model.chat} always wins — this never overrides a deliberate
 *       choice.</li>
 *   <li>Exactly one provider key present: that provider is selected, and its key is copied to the
 *       property Spring AI reads.</li>
 *   <li>Several keys present: <strong>fail</strong>, naming the providers found and the property
 *       that settles it. Guessing a winner by precedence would silently bill the wrong account and
 *       silently change the assistant's behaviour, and neither is discoverable from the outside.
 *       This follows Embabel, which likewise refuses to infer a default and requires the model to be
 *       named.</li>
 *   <li>No keys present: no provider is activated. Startup then fails where a chat model is
 *       actually needed rather than here, so that web-layer tests and other slices which never touch
 *       a model are unaffected.</li>
 * </ul>
 */
public class ChatModelProviderSelector implements EnvironmentPostProcessor {

    static final String PROVIDER_PROPERTY = "spring.ai.model.chat";
    static final String NO_PROVIDER = "none";

    /** Provider id as Spring AI names it, mapped to the environment variable holding its key. */
    static final Map<String, String> API_KEY_VARIABLES = Map.of(
            "openai", "OPENAI_API_KEY",
            "anthropic", "ANTHROPIC_API_KEY");

    private static final String PROPERTY_SOURCE_NAME = "solrMcpChatModelProvider";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (StringUtils.hasText(environment.getProperty(PROVIDER_PROPERTY))) {
            return;
        }

        List<String> available = API_KEY_VARIABLES.keySet().stream()
                .sorted()
                .filter(provider -> hasApiKey(environment, provider))
                .toList();

        if (available.size() > 1) {
            throw new IllegalStateException("""
                    Several chat model providers are configured: %s. This application uses one \
                    chat model, and choosing for you would silently pick which account is billed \
                    and how the assistant behaves. Set %s to one of %s, or unset the API keys you \
                    do not want used."""
                    .formatted(available, PROVIDER_PROPERTY, available));
        }

        Map<String, Object> selection = new LinkedHashMap<>();
        if (available.isEmpty()) {
            selection.put(PROVIDER_PROPERTY, NO_PROVIDER);
        } else {
            String provider = available.getFirst();
            selection.put(PROVIDER_PROPERTY, provider);
            apiKeyProperty(environment, provider).ifPresent(key ->
                    selection.put(springAiApiKeyProperty(provider), key));
        }
        // addLast: an explicitly configured spring.ai.<provider>.api-key must keep precedence over
        // the environment variable, so that a test cannot be handed a real key by accident.
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, selection));
    }

    private static boolean hasApiKey(ConfigurableEnvironment environment, String provider) {
        return StringUtils.hasText(environment.getProperty(springAiApiKeyProperty(provider)))
                || StringUtils.hasText(environment.getProperty(API_KEY_VARIABLES.get(provider)));
    }

    private static java.util.Optional<String> apiKeyProperty(ConfigurableEnvironment environment, String provider) {
        if (StringUtils.hasText(environment.getProperty(springAiApiKeyProperty(provider)))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(environment.getProperty(API_KEY_VARIABLES.get(provider)))
                .filter(StringUtils::hasText);
    }

    private static String springAiApiKeyProperty(String provider) {
        return "spring.ai." + provider + ".api-key";
    }
}
