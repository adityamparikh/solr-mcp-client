package org.apache.solr.mcp.client.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Cross-origin access for the REST API.
 *
 * <p>{@code X-AI-Conversation-Id} is the only carrier of the conversation, and a browser cannot read
 * a response header cross-origin unless the server names it in {@code Access-Control-Expose-Headers}.
 * Without that, a cross-origin UI silently sees no id and every request starts a new conversation —
 * a failure with no error to notice. Exposing it here is therefore part of the API contract, not a
 * convenience.
 *
 * <p>Allowed origins must be stated explicitly via {@code solr.mcp.client.cors.allowed-origins};
 * cross-origin access is off entirely when the list is empty, which suits the intended same-origin
 * deployment. The registration is not left to defaults on purpose: an unqualified
 * {@code addMapping} would allow every origin.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration(proxyBeanMethods = false)
class ApiCorsConfiguration implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    ApiCorsConfiguration(@Value("${solr.mcp.client.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                .exposedHeaders(SolrAssistantController.CONVERSATION_ID_HEADER);
    }
}
