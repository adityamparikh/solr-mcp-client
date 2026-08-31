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
package org.apache.solr.mcp.client;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Anthropic api-key placeholder in {@code application.yml} to the nested form.
 *
 * <p>The nesting is not stylistic. When neither variable is set the placeholder binds {@code ""},
 * and {@code AnthropicChatAutoConfiguration} guards the key with a null check rather than a blank
 * one — so {@code ""} is applied as a real key and suppresses {@code AnthropicSetup.detectApiKey()},
 * whose own chain reads ANTHROPIC_API_KEY and then ANTHROPIC_AUTH_TOKEN. Reading the token in the
 * placeholder is what keeps that second variable working.
 *
 * <p>Collapsing this to {@code ${ANTHROPIC_API_KEY:}} would break token-based authentication
 * silently: the context still starts, and the failure surfaces only as a 401 on the first chat
 * request. The expression is read out of the real file rather than restated here, so this fails if
 * someone simplifies it.
 */
class AnthropicApiKeyPlaceholderTest {

    private static final Pattern API_KEY_LINE =
            Pattern.compile("^\\s*api-key:\\s*(\\$\\{ANTHROPIC_API_KEY.*)$", Pattern.MULTILINE);

    // Spring Framework 7 dropped the four-argument constructor; the escape character is explicit now.
    private final PropertyPlaceholderHelper helper =
            new PropertyPlaceholderHelper("${", "}", ":", null, true);

    private String configuredExpression() throws IOException {
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = API_KEY_LINE.matcher(yaml);
        assertThat(matcher.find())
                .as("application.yml should configure an Anthropic api-key placeholder")
                .isTrue();
        return matcher.group(1).trim();
    }

    private String resolve(String expression, String apiKey, String authToken) {
        Properties properties = new Properties();
        if (apiKey != null) {
            properties.setProperty("ANTHROPIC_API_KEY", apiKey);
        }
        if (authToken != null) {
            properties.setProperty("ANTHROPIC_AUTH_TOKEN", authToken);
        }
        return helper.replacePlaceholders(expression, properties::getProperty);
    }

    @Test
    void prefersTheApiKeyWhenBothAreSet() throws IOException {
        assertThat(resolve(configuredExpression(), "from-api-key", "from-auth-token"))
                .isEqualTo("from-api-key");
    }

    @Test
    void fallsBackToTheAuthTokenWhenOnlyItIsSet() throws IOException {
        assertThat(resolve(configuredExpression(), null, "from-auth-token"))
                .isEqualTo("from-auth-token");
    }

    @Test
    void resolvesToEmptyWhenNeitherIsSet() throws IOException {
        // Empty rather than unresolved: an unresolvable placeholder would fail every context that
        // starts without a key, including slices that never build a ChatModel.
        assertThat(resolve(configuredExpression(), null, null)).isEmpty();
    }
}
