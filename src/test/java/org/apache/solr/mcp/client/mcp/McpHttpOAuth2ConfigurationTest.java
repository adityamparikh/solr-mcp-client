package org.apache.solr.mcp.client.mcp;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class McpHttpOAuth2ConfigurationTest {

    private final AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            mock(AuthorizedClientServiceOAuth2AuthorizedClientManager.class);
    private final McpSyncHttpClientRequestCustomizer customizer =
            new McpHttpOAuth2Configuration().solrMcpBearerTokenCustomizer(manager);

    @Test
    void attachesTheServiceTokenAsABearerHeader() {
        given(manager.authorize(any(OAuth2AuthorizeRequest.class))).willReturn(authorizedClient("token-abc"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://solr-mcp.example.com/mcp"));

        customizer.customize(builder, "POST", URI.create("https://solr-mcp.example.com/mcp"),
                "{}", McpTransportContext.EMPTY);

        assertThat(builder.build().headers().firstValue("Authorization")).hasValue("Bearer token-abc");
    }

    @Test
    void failsLoudlyWhenNoTokenCanBeObtained() {
        given(manager.authorize(any(OAuth2AuthorizeRequest.class))).willReturn(null);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://solr-mcp.example.com/mcp"));

        assertThatIllegalStateException()
                .isThrownBy(() -> customizer.customize(builder, "POST",
                        URI.create("https://solr-mcp.example.com/mcp"), "{}", McpTransportContext.EMPTY))
                .withMessageContaining("Unable to obtain an OAuth2 access token");
    }

    private static OAuth2AuthorizedClient authorizedClient(String tokenValue) {
        ClientRegistration registration = ClientRegistration
                .withRegistrationId(McpHttpOAuth2Configuration.REGISTRATION_ID)
                .clientId("cid")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("https://idp.example.com/token")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, tokenValue,
                Instant.now(), Instant.now().plusSeconds(300));
        return new OAuth2AuthorizedClient(registration, "solr-mcp-client", token);
    }
}
