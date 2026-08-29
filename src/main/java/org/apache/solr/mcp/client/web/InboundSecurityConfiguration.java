package org.apache.solr.mcp.client.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The REST facade is deliberately unauthenticated in both profiles.
 *
 * <p>OAuth2 in the {@code mcp-http} profile secures the <em>outbound</em> MCP connection only, using a
 * dedicated service token. This application performs no inbound authentication by design; deploy it
 * behind the hosting application's security boundary and never expose it directly.
 *
 * <p>The chain is conditional on a servlet web application so that non-web contexts (slice tests,
 * future CLI entry points) can start without a {@code HttpSecurity} bean being required.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration(proxyBeanMethods = false)
class InboundSecurityConfiguration {

    @Bean
    SecurityFilterChain inboundSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless JSON API with no browser-managed session or cookie credentials.
                .csrf(AbstractHttpConfigurer::disable)
                // Defers to ApiCorsConfiguration; without this the security chain would ignore it.
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
