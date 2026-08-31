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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

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

    /**
     * Corrects two lies springdoc tells about the conversation header on its own.
     *
     * <p>It marks the header {@code required: true} because of the {@code @NotBlank} on the
     * parameter — but that constraint only forbids a <em>blank</em> header, never an absent one,
     * and the whole contract is that omitting it starts a new conversation. An explicit
     * {@code @Parameter(required = false)} cannot fix this: {@code false} is the annotation
     * attribute's default, so springdoc cannot tell "stated" from "unset" and its bean-validation
     * pass wins.
     *
     * <p>It also resolves the {@code #{...}} default expression once while building the document,
     * baking a single random UUID in as the schema default — which Swagger UI then pre-fills and
     * sends, silently routing every UI caller into the same conversation.
     *
     * <p>An {@link OpenApiCustomizer} sees the finished document, after both derivations — a
     * {@code ParameterCustomizer} would be the natural hook, but springdoc applies the resolved
     * default value after those run, so the correction has to happen here.
     */
    @Bean
    OpenApiCustomizer conversationHeaderIsOptionalWithNoDefault() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .flatMap(operation -> operation.getParameters() == null
                            ? Stream.empty()
                            : operation.getParameters().stream())
                    .filter(parameter -> SolrAssistantController.CONVERSATION_ID_HEADER
                            .equals(parameter.getName()))
                    .forEach(parameter -> {
                        parameter.setRequired(false);
                        // A fresh schema rather than setDefault(null): the resolved default also
                        // lives in JsonSchema's raw 3.1 state, which nulling the typed field does
                        // not reach.
                        parameter.setSchema(new StringSchema().minLength(1));
                    });
        };
    }
}
