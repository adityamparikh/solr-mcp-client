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

import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatReply;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.apache.solr.mcp.client.web.SolrAssistantController.CONVERSATION_ID_HEADER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// spring.ai.model.chat is pinned throughout so these slices do not depend on which model API
// keys a developer happens to have exported.
class ApiCorsConfigurationTest {

    private static final String UI_ORIGIN = "https://solr-ui.example.com";
    private static final String BODY = """
            {"message":"Find documents about SolrCloud"}""";

    @Nested
    @WebMvcTest(controllers = SolrAssistantController.class,
            properties = {"spring.ai.model.chat=none", "solr.mcp.client.cors.allowed-origins=" + UI_ORIGIN})
    class WhenAnOriginIsAllowed {

        @Autowired MockMvc mockMvc;
        @MockitoBean SolrAssistant assistant;

        @Test
        void exposesTheConversationHeaderToTheBrowser() throws Exception {
            given(assistant.send(anyString(), any(ChatRequest.class))).willReturn(new ChatReply("ok"));

            // Without this the browser reads null for X-AI-Conversation-Id and every request looks
            // like a new conversation, with no error to notice.
            mockMvc.perform(post("/api/v1/chat").header(HttpHeaders.ORIGIN, UI_ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, UI_ORIGIN))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            CONVERSATION_ID_HEADER));
        }

        @Test
        void refusesAnOriginThatWasNotAllowed() throws Exception {
            mockMvc.perform(post("/api/v1/chat").header(HttpHeaders.ORIGIN, "https://evil.example.com")
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @WebMvcTest(controllers = SolrAssistantController.class,
            properties = {"spring.ai.model.chat=none",
                    "solr.mcp.client.cors.allowed-origins=" + UI_ORIGIN + ",https://admin.example.com"})
    class WhenSeveralOriginsAreAllowed {

        @Autowired MockMvc mockMvc;
        @MockitoBean SolrAssistant assistant;

        @Test
        void admitsEachOfThem() throws Exception {
            given(assistant.send(anyString(), any(ChatRequest.class))).willReturn(new ChatReply("ok"));

            // A comma-separated value must become several origins, not one nonsensical origin.
            for (String origin : new String[]{UI_ORIGIN, "https://admin.example.com"}) {
                mockMvc.perform(post("/api/v1/chat").header(HttpHeaders.ORIGIN, origin)
                                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin));
            }
        }
    }

    @Nested
    @WebMvcTest(controllers = SolrAssistantController.class, properties = "spring.ai.model.chat=none")
    class WhenNoOriginIsConfigured {

        @Autowired MockMvc mockMvc;
        @MockitoBean SolrAssistant assistant;

        @Test
        void allowsNoCrossOriginAccessAtAll() throws Exception {
            // The default deployment is same-origin; an unqualified addMapping would have allowed
            // every origin instead.
            mockMvc.perform(post("/api/v1/chat").header(HttpHeaders.ORIGIN, UI_ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
