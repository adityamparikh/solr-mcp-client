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

import io.modelcontextprotocol.spec.McpTransportException;
import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.apache.solr.mcp.client.web.SolrAssistantController.CONVERSATION_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// spring.ai.model.chat is pinned so the slice does not depend on which model API keys a
// developer happens to have exported.
@WebMvcTest(controllers = SolrAssistantController.class, properties = "spring.ai.model.chat=none")
class SolrAssistantControllerTest {

    private static final String VALID_BODY = """
            {"message":"Find documents about SolrCloud"}""";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SolrAssistant assistant;

    @Test
    void continuesTheConversationNamedInTheHeader() throws Exception {
        given(assistant.ask("user-7:session-4", "Find documents about SolrCloud"))
                .willReturn("I found 3 documents.");

        mockMvc.perform(post("/api/v1/chat")
                        .header(CONVERSATION_ID_HEADER, "user-7:session-4")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                // The conversation travels in the header in both directions; the body carries only
                // the answer.
                .andExpect(header().string(CONVERSATION_ID_HEADER, "user-7:session-4"))
                .andExpect(jsonPath("$.content").value("I found 3 documents."))
                .andExpect(jsonPath("$.conversationId").doesNotExist());
    }

    @Test
    void startsAFreshConversationWhenTheHeaderIsAbsent() throws Exception {
        given(assistant.ask(anyString(), anyString())).willReturn("Hello.");

        String issued = mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(CONVERSATION_ID_HEADER);

        // No shared "default" bucket: an omitted header must not join other callers' memory.
        assertThat(issued).isNotBlank().isNotEqualTo("default");
        then(assistant).should().ask(eq(issued), anyString());
    }

    @Test
    void issuesADistinctConversationForEachAnonymousRequest() throws Exception {
        given(assistant.ask(anyString(), anyString())).willReturn("Hello.");

        String first = issuedConversationId();
        String second = issuedConversationId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsAnOversizedConversationIdHeader() throws Exception {
        mockMvc.perform(post("/api/v1/chat")
                        .header(CONVERSATION_ID_HEADER, "x".repeat(SolrAssistantController.MAX_CONVERSATION_ID_LENGTH + 1))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("conversationId is too long"));

        then(assistant).should(never()).ask(anyString(), anyString());
    }

    @Test
    void treatsEverySpellingOfVersionOneAsTheSameVersion() throws Exception {
        given(assistant.ask(anyString(), anyString())).willReturn("ok");

        // Versions are compared semantically, not textually.
        for (String segment : new String[]{"v1", "1", "1.0", "1.0.0"}) {
            mockMvc.perform(chat("/api/" + segment + "/chat"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void stillNeedsTheVersionSegmentEvenThoughV1IsTheDefaultVersion() throws Exception {
        // spring.mvc.apiversion.default makes 1.0 the version for a request that resolves none, but
        // it cannot make an unversioned URL reach a handler: the segment is part of the mapping
        // template, so this is a 404 rather than a 400 about the version.
        mockMvc.perform(chat("/api/chat"))
                .andExpect(status().isNotFound());

        then(assistant).should(never()).ask(anyString(), anyString());
    }

    @Test
    void rejectsAnUnsupportedApiVersion() throws Exception {
        // An unknown version is a 400 about the version, not a 404 that looks like a wrong URL.
        mockMvc.perform(chat("/api/v9/chat"))
                .andExpect(status().isBadRequest());

        then(assistant).should(never()).ask(anyString(), anyString());
    }

    @Test
    void rejectsAnUnparseableVersionSegment() throws Exception {
        mockMvc.perform(chat("/api/banana/chat"))
                .andExpect(status().isBadRequest());

        then(assistant).should(never()).ask(anyString(), anyString());
    }

    @Test
    void rejectsABlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("message must not be blank"));
    }

    @Test
    void rejectsAnOversizedMessage() throws Exception {
        String tooLong = "x".repeat(SolrAssistantController.MAX_MESSAGE_LENGTH + 1);

        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"%s"}""".formatted(tooLong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("message is too long"));
    }

    @Test
    void reportsATimedOutTransportAsGatewayTimeout() throws Exception {
        given(assistant.ask(anyString(), anyString()))
                .willThrow(new McpTransportException("stream closed while awaiting the tool result"));

        mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.detail").value("The Solr assistant is temporarily unavailable. Please retry."));
    }

    @Test
    void reportsAFailedServiceTokenAsBadGatewayWithoutLeakingTheCause() throws Exception {
        given(assistant.ask(anyString(), anyString())).willThrow(new OAuth2AuthorizationException(
                new OAuth2Error("invalid_client", "bad secret for https://idp.internal/token", null)));

        mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The Solr assistant could not complete the request."));
    }

    @Test
    void forgetsAConversation() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/{conversationId}", "user-7:session-4"))
                .andExpect(status().isNoContent());

        then(assistant).should().forget("user-7:session-4");
    }

    @Test
    void rejectsAnOversizedConversationIdOnDelete() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/{conversationId}",
                        "x".repeat(SolrAssistantController.MAX_CONVERSATION_ID_LENGTH + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("conversationId is too long"));

        then(assistant).should(never()).forget(anyString());
    }

    private static MockHttpServletRequestBuilder chat(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY);
    }

    private String issuedConversationId() throws Exception {
        return mockMvc.perform(chat("/api/v1/chat"))
                .andReturn().getResponse().getHeader(CONVERSATION_ID_HEADER);
    }
}
