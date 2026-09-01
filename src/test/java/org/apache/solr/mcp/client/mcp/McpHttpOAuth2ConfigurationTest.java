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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import org.apache.solr.mcp.client.observability.TracePropagatingHttpRequestCustomizer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transport builder holds a single request customizer, so the configuration composes two
 * concerns into one — and both must survive the composition. The token half is exercised through
 * a request-free manager mock, which is also the point pinned by the original version of this
 * test: mcp-client-security's auto-configuration would install its authorization-code variant,
 * which resolves the token from an authenticated user and therefore has none during startup;
 * obtaining a token here with no user proves the client-credentials path is the one installed.
 */
class McpHttpOAuth2ConfigurationTest {

    private static final URI ENDPOINT = URI.create("https://solr-mcp.example.com/mcp");

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            mock(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);

    private final Tracer tracer = mock(Tracer.class);

    private final Propagator propagator = mock(Propagator.class);

    @Test
    void installsACustomizerCarryingBothTokenAndTraceContext() {
        var transport = mock(HttpClientStreamableHttpTransport.Builder.class);
        when(manager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(
                clientRegistration(), "solr-mcp-client", accessToken()));
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        doAnswer(invocation -> {
            Propagator.Setter<HttpRequest.Builder> setter = invocation.getArgument(2);
            setter.set(invocation.getArgument(1), "traceparent", TRACEPARENT);
            return null;
        }).when(propagator).inject(eq(traceContext), any(), any());

        new McpHttpOAuth2Configuration().solrMcpRequestCustomizer(manager, tracer, propagator)
                .customize("solr-mcp", transport);

        ArgumentCaptor<McpSyncHttpClientRequestCustomizer> installed =
                ArgumentCaptor.forClass(McpSyncHttpClientRequestCustomizer.class);
        then(transport).should().httpRequestCustomizer(installed.capture());

        HttpRequest.Builder request = HttpRequest.newBuilder(ENDPOINT);
        installed.getValue().customize(request, "POST", ENDPOINT, "{}", McpTransportContext.EMPTY);

        var headers = request.build().headers();
        assertThat(headers.firstValue("Authorization")).contains("Bearer test-token");
        assertThat(headers.firstValue("traceparent")).contains(TRACEPARENT);
    }

    /**
     * The provider is what runs on the calling thread, so it is where the span must be captured;
     * the customizer above only reads what it stored. A context produced with no current span must
     * still answer for other keys — the authentication the delegated provider stores, for one.
     */
    @Test
    void transportContextProviderCapturesTheCurrentTraceContext() {
        var spec = mock(io.modelcontextprotocol.client.McpClient.SyncSpec.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);

        new McpHttpOAuth2Configuration().solrMcpTransportContextCustomizer(tracer)
                .customize("solr-mcp", spec);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Supplier<McpTransportContext>> provider =
                ArgumentCaptor.forClass(java.util.function.Supplier.class);
        then(spec).should().transportContextProvider(provider.capture());
        assertThat(provider.getValue().get()
                .get(TracePropagatingHttpRequestCustomizer.TRACE_CONTEXT_KEY)).isSameAs(traceContext);

        when(tracer.currentSpan()).thenReturn(null);
        assertThat(provider.getValue().get()
                .get(TracePropagatingHttpRequestCustomizer.TRACE_CONTEXT_KEY)).isNull();
    }

    private ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId(McpHttpOAuth2Configuration.REGISTRATION_ID)
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://idp.example.com/token")
                .build();
    }

    private OAuth2AccessToken accessToken() {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token",
                Instant.now(), Instant.now().plusSeconds(300));
    }
}
