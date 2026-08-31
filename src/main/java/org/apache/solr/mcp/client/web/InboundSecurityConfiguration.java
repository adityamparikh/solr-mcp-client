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
 * The REST facade is deliberately unauthenticated in every profile.
 *
 * <p>OAuth2 in the {@code mcp-http} profile secures the <em>outbound</em> MCP connection only, using a
 * dedicated service token. This application performs no inbound authentication by design; deploy it
 * behind the hosting application's security boundary and never expose it directly. A profile only
 * ever chooses how Solr MCP is reached, so no profile relaxes or tightens what is served here.
 *
 * <p>The chain is conditional on a servlet web application so that non-web contexts (slice tests,
 * future CLI entry points) can start without a {@code HttpSecurity} bean being required.
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration(proxyBeanMethods = false)
class InboundSecurityConfiguration {

    /**
     * Permits every request, per the class contract. Declaring the chain explicitly rather than
     * leaving Boot's default is the point: the default chain would demand authentication the
     * moment Spring Security landed on the classpath for the outbound OAuth2 support.
     */
    @Bean
    SecurityFilterChain inboundSecurityFilterChain(HttpSecurity http) {
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
