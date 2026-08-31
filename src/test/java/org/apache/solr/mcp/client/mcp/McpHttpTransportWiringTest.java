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

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the real application context in the {@code mcp-http} profile.
 *
 * <p>Every assertion here guards a defect that a compiling, unit-tested build shipped anyway:
 * the chat client and the bearer-token customizer were both declared with {@code @ConditionalOnBean}
 * on user configuration, which never matches, and the OAuth2 manager was the request-bound variant
 * that cannot issue a token during MCP client initialization.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.initialized=false",
        "spring.ai.anthropic.api-key=test-key",
        "SOLR_MCP_HTTP_URL=https://solr-mcp.example.com",
        "SOLR_MCP_OAUTH_CLIENT_ID=test-client",
        "SOLR_MCP_OAUTH_CLIENT_SECRET=test-secret",
        "SOLR_MCP_OAUTH_TOKEN_URI=https://idp.example.com/token"
})
@AutoConfigureMockMvc
@ActiveProfiles("mcp-http")
class McpHttpTransportWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mockMvc;

    @Test
    void exposesTheSolrAssistantChatClient() {
        assertThat(context.getBean("chatClient")).isInstanceOf(ChatClient.class);
    }

    /**
     * Exactly one, and ours: mcp-client-security auto-configures its own
     * {@code McpClientCustomizer} for a pre-registered client, but that one resolves the token from
     * an authenticated user. Ours suppresses it by occupying the type its
     * {@code @ConditionalOnMissingBean} guards, so a second bean here means the user-token
     * customizer is back and startup will have no token to send.
     */
    @Test
    void registersOnlyTheClientCredentialsCustomizerWithTheMcpTransport() {
        // Other McpClientCustomizer beans are Spring AI's own and unrelated to authorization;
        // preRegisteredClientCustomizer is the one that must not be here.
        assertThat(context.getBeanNamesForType(McpClientCustomizer.class))
                .contains("solrMcpBearerTokenCustomizer")
                .doesNotContain("preRegisteredClientCustomizer");
    }

    @Test
    void usesARequestFreeAuthorizedClientManagerForTheServiceToken() {
        assertThat(context.getBean(OAuth2AuthorizedClientManager.class))
                .isInstanceOf(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
    }

    @Test
    void documentsTheApi() throws Exception {
        assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("solr-mcp-client");

        // springdoc resolves the {version} template to the declared version rather than leaking
        // it as a path parameter, so the document describes a concrete, callable URL.
        String document = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(document).contains("/api/v1/chat").doesNotContain("{version}");
    }

    @Test
    void leavesTheRestFacadeUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}"""))
                .andExpect(status().isBadRequest());
    }
}
