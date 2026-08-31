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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

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

    /**
     * Error responses must advertise {@code ProblemDetail}, which is what
     * {@code ProblemDetailExceptionHandler} actually returns.
     *
     * <p>Guards a defect the document shipped with: an {@code @ApiResponse} that declares only a
     * description does not declare an empty body — springdoc keeps the schema derived from the
     * handler method's return type, so 400, 502 and 504 all claimed a {@code ChatReply} body. A
     * generated client would have reproduced that faithfully, and no other test could see it.
     */
    @Test
    void documentsErrorsAsProblemDetail() throws Exception {
        String document = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode chat = new ObjectMapper().readTree(document)
                .at("/paths/~1api~1v1~1chat/post/responses");
        for (String errorCode : List.of("400", "502", "504")) {
            assertThat(chat.at("/" + errorCode + "/content/application~1problem+json/schema/$ref"))
                    .as("%s must be documented as ProblemDetail", errorCode)
                    .hasToString("\"#/components/schemas/ProblemDetail\"");
        }
        assertThat(chat.at("/200/content/application~1json/schema/$ref"))
                .hasToString("\"#/components/schemas/ChatReply\"");
    }

    /**
     * The conversation header must be documented as optional with no default value.
     *
     * <p>Guards two lies the document told. {@code @NotBlank} on the parameter made springdoc mark
     * the header {@code required: true}, contradicting "omit to start a new one" — the constraint
     * only forbids a <em>blank</em> header, never an absent one. And springdoc resolves the
     * {@code #{...}} default expression once while building the document, baking a single random
     * UUID in as the header's default — which Swagger UI then pre-fills and sends, silently routing
     * every UI caller into the same conversation.
     */
    @Test
    void documentsTheConversationHeaderAsOptionalWithNoDefault() throws Exception {
        String document = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode header = new ObjectMapper().readTree(document)
                .at("/paths/~1api~1v1~1chat/post/parameters/0");
        assertThat(header.at("/name").asText()).isEqualTo("X-AI-Conversation-Id");
        assertThat(header.at("/required").asBoolean(false)).isFalse();
        assertThat(header.at("/schema/default").isMissingNode())
                .as("the SpEL default must not be baked into the document as a fixed UUID")
                .isTrue();
    }

    @Test
    void leavesTheRestFacadeUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}"""))
                .andExpect(status().isBadRequest());
    }
}
