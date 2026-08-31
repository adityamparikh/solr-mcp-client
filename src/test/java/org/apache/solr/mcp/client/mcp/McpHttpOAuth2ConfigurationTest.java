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

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2ClientCredentialsSyncHttpRequestCustomizer;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * The token is applied by mcp-client-security, so what is worth pinning here is which of its
 * customizers gets installed. Its auto-configuration would install the authorization-code variant,
 * which resolves a token from an authenticated user and therefore has none during startup; this
 * client calls Solr MCP on its own behalf and needs the client-credentials variant.
 */
class McpHttpOAuth2ConfigurationTest {

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            mock(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);

    @Test
    void installsTheClientCredentialsCustomizerOnTheTransport() {
        var transport = mock(HttpClientStreamableHttpTransport.Builder.class);

        new McpHttpOAuth2Configuration().solrMcpBearerTokenCustomizer(manager)
                .customize("solr-mcp", transport);

        ArgumentCaptor<McpSyncHttpClientRequestCustomizer> installed =
                ArgumentCaptor.forClass(McpSyncHttpClientRequestCustomizer.class);
        then(transport).should().httpRequestCustomizer(installed.capture());
        assertThat(installed.getValue()).isInstanceOf(OAuth2ClientCredentialsSyncHttpRequestCustomizer.class);
    }
}
