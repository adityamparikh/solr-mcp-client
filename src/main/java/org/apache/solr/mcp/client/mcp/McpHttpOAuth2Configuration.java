package org.apache.solr.mcp.client.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
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
 * Attaches a dedicated service token to every outbound Solr MCP request in the {@code mcp-http} profile.
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

    static final String REGISTRATION_ID = "solr-mcp";

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

    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> solrMcpBearerTokenCustomizer(
            AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager) {
        return (name, transport) -> transport.httpRequestCustomizer(
                new OAuth2ClientCredentialsSyncHttpRequestCustomizer(authorizedClientManager, REGISTRATION_ID));
    }
}
