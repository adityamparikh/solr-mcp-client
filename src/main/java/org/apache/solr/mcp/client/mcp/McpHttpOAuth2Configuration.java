package org.apache.solr.mcp.client.mcp;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Attaches a dedicated service token to every outbound Solr MCP request in the {@code mcp-http} profile.
 *
 * <p>The manager is built explicitly rather than injected: the manager Spring Security registers by
 * default is {@code DefaultOAuth2AuthorizedClientManager}, which resolves the authorized client from
 * the current {@code HttpServletRequest} and fails with "servletRequest cannot be null" whenever the
 * MCP client talks to the server off a request thread — which includes client initialization at
 * startup. {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} is the request-free variant
 * intended for {@code client_credentials} service-to-service flows.
 */
@Profile("mcp-http")
@Configuration(proxyBeanMethods = false)
class McpHttpOAuth2Configuration {

    static final String REGISTRATION_ID = "solr-mcp";

    private static final String PRINCIPAL = "solr-mcp-client";

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
     * Picked up by Spring AI's streamable-HTTP transport auto-configuration, which collects
     * {@link McpSyncHttpClientRequestCustomizer} beans and applies them to every request.
     */
    @Bean
    McpSyncHttpClientRequestCustomizer solrMcpBearerTokenCustomizer(
            AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager) {
        return (request, method, uri, body, context) -> {
            OAuth2AuthorizedClient client = authorizedClientManager.authorize(OAuth2AuthorizeRequest
                    .withClientRegistrationId(REGISTRATION_ID)
                    .principal(PRINCIPAL)
                    .build());
            if (client == null) {
                throw new IllegalStateException("Unable to obtain an OAuth2 access token for Solr MCP");
            }
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
        };
    }
}
