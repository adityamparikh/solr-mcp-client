package org.apache.solr.mcp.client.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the REST facade for the OpenAPI document served at {@code /api-docs}.
 *
 * <p>No security scheme is declared: the facade performs no inbound authentication by design (see
 * {@link InboundSecurityConfiguration}), and advertising one it does not enforce would misrepresent
 * the contract to generated clients.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI solrMcpClientOpenApi(@Value("${spring.application.name}") String applicationName) {
        return new OpenAPI().info(new Info()
                .title(applicationName)
                .version(SolrAssistantController.V1)
                .description("""
                        REST facade over an Apache Solr MCP server. A chat model reaches Solr \
                        through MCP tools; this API exposes that assistant over HTTP so a user \
                        interface does not have to embed Spring AI itself.

                        The facade is unauthenticated by design and is intended to run inside a \
                        trusted boundary. In the `mcp-http` profile, OAuth2 client credentials secure \
                        the outbound connection to Solr MCP only.""")
                .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
