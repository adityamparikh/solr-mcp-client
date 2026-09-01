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
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.function.Supplier;
import org.apache.solr.mcp.client.observability.TracePropagatingHttpRequestCustomizer;
import org.springaicommunity.mcp.security.client.sync.AuthenticationMcpTransportContextProvider;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2ClientCredentialsSyncHttpRequestCustomizer;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Attaches a dedicated service token to every outbound Solr MCP request in the {@code mcp-http}
 * profile, and propagates the current trace context alongside it — see
 * {@link org.apache.solr.mcp.client.observability.TracePropagatingHttpRequestCustomizer} for why
 * the MCP transport needs that help.
 *
 * <p>The token itself is applied by {@code mcp-client-security}, the client-side half of the library
 * whose server half secures the Solr MCP server. Its Boot auto-configuration does not fit as it
 * stands: {@code HttpClientStreamableHttpTransportAutoConfiguration} defaults a pre-registered client
 * to {@link org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2AuthorizationCodeSyncHttpRequestCustomizer},
 * which resolves the token from an authenticated user. This client has no user — it calls Solr MCP on
 * its own behalf, including at startup — so the customizer below replaces that default. Declaring it
 * as {@link McpClientCustomizer} rather than a bare request customizer is what suppresses it, because
 * that is the type the auto-configuration guards with {@code @ConditionalOnMissingBean}.
 *
 * <p>The manager is built explicitly rather than injected: the manager Spring Security registers by
 * default is {@code DefaultOAuth2AuthorizedClientManager}, which resolves the authorized client from
 * the current {@code HttpServletRequest} and fails with "servletRequest cannot be null" whenever the
 * MCP client talks to the server off a request thread — which includes client initialization at
 * startup. {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} is the request-free variant
 * intended for {@code client_credentials} service-to-service flows, and is the type the library's
 * client-credentials customizer requires.
 */
@Profile("mcp-http")
@Configuration(proxyBeanMethods = false)
class McpHttpOAuth2Configuration {

    /**
     * Selects the {@code spring.security.oauth2.client.registration} entry to obtain the token from.
     * It must match the key used in {@code application-mcp-http.yml}; nothing validates the pairing
     * at startup, so a rename on either side surfaces only as a failed token request.
     */
    static final String REGISTRATION_ID = "solr-mcp";

    /**
     * The request-free manager for the {@code client_credentials} flow; the class Javadoc covers
     * why Spring Security's default request-bound manager cannot serve here. Only the
     * client-credentials provider is registered, so no other grant can be triggered by accident.
     */
    @Bean
    AuthorizedClientServiceOAuth2AuthorizedClientManager mcpAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /**
     * Attaches the service token and the current trace context to every outbound Solr MCP request.
     * Declared as a {@link McpClientCustomizer} deliberately: that is the type the library's
     * auto-configuration guards with {@code @ConditionalOnMissingBean}, so this bean is also what
     * suppresses the user-bound authorization-code default described in the class Javadoc.
     *
     * <p>The two concerns are composed into one lambda because the transport builder holds a
     * single request customizer — a second {@code httpRequestCustomizer(...)} call replaces the
     * first rather than adding to it, so registering trace propagation separately would silently
     * drop the bearer token (or the reverse, depending on order).
     */
    // NullAway is suppressed for one annotation conflict: the MCP SDK declares the request body
    // @Nullable (GET and DELETE requests carry none) while mcp-client-security 0.1.x declares it
    // non-null. The library never dereferences the body — it only attaches the Authorization
    // header — so passing the SDK's nullable value through is safe; the 0.x annotation is the
    // defect, in keeping with this library's documented looseness (see libs.versions.toml).
    @SuppressWarnings("NullAway")
    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> solrMcpRequestCustomizer(
            AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager,
            Tracer tracer,
            Propagator propagator) {
        var bearerToken = new OAuth2ClientCredentialsSyncHttpRequestCustomizer(authorizedClientManager, REGISTRATION_ID);
        var tracePropagation = new TracePropagatingHttpRequestCustomizer(tracer, propagator);
        return (name, transport) -> transport.httpRequestCustomizer((builder, method, endpoint, body, context) -> {
            bearerToken.customize(builder, method, endpoint, body, context);
            tracePropagation.customize(builder, method, endpoint, body, context);
        });
    }

    /**
     * Captures the caller's trace context into the transport context at call time. The SDK
     * evaluates this provider on the calling thread when the sync client subscribes, then carries
     * the result through its Reactor pipeline to the request customizer above — which runs on a
     * transport worker thread where the span thread-local is empty, so reading it there directly
     * finds nothing. This capture-and-carry is the SDK's designed route for request-scoped data.
     *
     * <p>Typed {@code McpClientCustomizer<McpClient.SyncSpec>} deliberately, the same
     * occupy-the-guarded-type move as the bean above: mcp-client-security auto-configures a
     * customizer of this exact type to install its {@link AuthenticationMcpTransportContextProvider},
     * guarded with {@code @ConditionalOnMissingBean} — so declaring a second provider-setting
     * customizer would race it for the spec's single provider slot. This bean replaces it and
     * delegates to that same provider, layering the trace context on top, so nothing the library
     * captures is lost.
     */
    @Bean
    McpClientCustomizer<McpClient.SyncSpec> solrMcpTransportContextCustomizer(Tracer tracer) {
        Supplier<McpTransportContext> authentication = new AuthenticationMcpTransportContextProvider();
        return (name, spec) -> spec.transportContextProvider(() -> {
            McpTransportContext base = authentication.get();
            Span span = tracer.currentSpan();
            if (span == null) {
                return base;
            }
            TraceContext traceContext = span.context();
            return key -> TracePropagatingHttpRequestCustomizer.TRACE_CONTEXT_KEY.equals(key)
                    ? traceContext
                    : base.get(key);
        });
    }
}
