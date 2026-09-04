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
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatReply;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Flux;

import static java.util.Objects.requireNonNull;
import static org.apache.solr.mcp.client.web.SolrAssistantController.CONVERSATION_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        given(assistant.send("user-7:session-4", new ChatRequest("Find documents about SolrCloud")))
                .willReturn(new ChatReply("I found 3 documents."));

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
        given(assistant.send(anyString(), any(ChatRequest.class))).willReturn(new ChatReply("Hello."));

        String issued = mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(CONVERSATION_ID_HEADER);

        // No shared "default" bucket: an omitted header must not join other callers' memory.
        assertThat(issued).isNotBlank().isNotEqualTo("default");
        then(assistant).should().send(eq(issued), any(ChatRequest.class));
    }

    @Test
    void issuesADistinctConversationForEachAnonymousRequest() throws Exception {
        given(assistant.send(anyString(), any(ChatRequest.class))).willReturn(new ChatReply("Hello."));

        String first = issuedConversationId();
        String second = issuedConversationId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void treatsEverySpellingOfVersionOneAsTheSameVersion() throws Exception {
        given(assistant.send(anyString(), any(ChatRequest.class))).willReturn(new ChatReply("ok"));

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

        then(assistant).should(never()).send(anyString(), any(ChatRequest.class));
    }

    @Test
    void rejectsAnUnsupportedApiVersion() throws Exception {
        // An unknown version is a 400 about the version, not a 404 that looks like a wrong URL.
        mockMvc.perform(chat("/api/v9/chat"))
                .andExpect(status().isBadRequest());

        then(assistant).should(never()).send(anyString(), any(ChatRequest.class));
    }

    @Test
    void rejectsAnUnparseableVersionSegment() throws Exception {
        mockMvc.perform(chat("/api/banana/chat"))
                .andExpect(status().isBadRequest());

        then(assistant).should(never()).send(anyString(), any(ChatRequest.class));
    }

    @Test
    void rejectsABlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}"""))
                .andExpect(status().isBadRequest())
                // The OpenAPI document advertises application/problem+json for every error; this
                // pins the runtime to it so the two cannot drift.
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("message must not be blank"));
    }

    @Test
    void reportsATimedOutTransportAsGatewayTimeout() throws Exception {
        given(assistant.send(anyString(), any(ChatRequest.class)))
                .willThrow(new McpTransportException("stream closed while awaiting the tool result"));

        mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The Solr assistant is temporarily unavailable. Please retry."));
    }

    @Test
    void reportsAFailedServiceTokenAsBadGatewayWithoutLeakingTheCause() throws Exception {
        given(assistant.send(anyString(), any(ChatRequest.class))).willThrow(new OAuth2AuthorizationException(
                new OAuth2Error("invalid_client", "bad secret for https://idp.internal/token", null)));

        mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The Solr assistant could not complete the request."));
    }

    @Test
    void reportsAnEmptyModelAnswerAsBadGateway() throws Exception {
        // ChatClient reports "completed with no content" as a null body. It must surface as an
        // upstream failure, not as a 200 carrying {"content": null}.
        given(assistant.send(anyString(), any(ChatRequest.class))).willThrow(
                new SolrAssistant.EmptyAnswerException("The chat model returned no content for conversation c-1"));

        mockMvc.perform(chat("/api/v1/chat"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The Solr assistant could not complete the request."));
    }

    @Test
    void streamsTheAnswerAsPlainText() throws Exception {
        given(assistant.stream("user-7:session-4", new ChatRequest("Find documents about SolrCloud")))
                .willReturn(Flux.just("I found ", "3 ", "documents."));

        String body = streamBody(post("/api/v1/stream")
                .header(CONVERSATION_ID_HEADER, "user-7:session-4")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        // The body is the answer and nothing else: no framing to strip, so a client appends what
        // arrives and is done.
        assertThat(body).isEqualTo("I found 3 documents.");
    }

    @Test
    void deliversWhitespaceAndNewlinesExactlyAsTheModelProducedThem() throws Exception {
        // Why this endpoint is not an event stream. SSE has no escaping: SseEmitter writes
        // "data:" followed by the delta verbatim, so a leading space landed where the SSE spec
        // mandates a client strip one ("Yourfilms"), and an embedded newline was rewritten to
        // "\ndata:", splitting one delta across frames. Plain text has neither hazard, and this
        // test is what keeps the endpoint honest about it.
        given(assistant.stream(anyString(), any(ChatRequest.class)))
                .willReturn(Flux.just("Your", " films", " collection.\n\n", "- .45\n", "- 8 Mile"));

        String body = streamBody(post("/api/v1/stream")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        assertThat(body).isEqualTo("""
                Your films collection.

                - .45
                - 8 Mile""");
    }

    @Test
    void streamsNonAsciiAnswersAsUtf8() throws Exception {
        // The charset has to be pinned on the response. ReactiveTypeHandler stamps the media type
        // it is given onto the Content-Type header, so a bare text/plain would advertise no
        // charset and leave a client free to decode UTF-8 bytes as ISO-8859-1.
        given(assistant.stream(anyString(), any(ChatRequest.class)))
                .willReturn(Flux.just("¿Quién es el señor ", "López?"));

        var response = mockMvc.perform(asyncDispatch(mockMvc.perform(post("/api/v1/stream")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andReturn()))
                .andReturn().getResponse();

        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("¿Quién es el señor López?");
        assertThat(response.getContentType()).isEqualToIgnoringCase("text/plain;charset=UTF-8");
    }

    @Test
    void refusesToServeTheStreamAsServerSentEvents() throws Exception {
        // This endpoint used to be an event stream, and ReactiveTypeHandler would still serve one
        // if asked: it picks its branch from the response's own Content-Type and falls back to
        // Accept when that is absent. Declaring produces=text/plain settles it in content
        // negotiation instead, so a client still asking for Server-Sent Events is refused outright
        // rather than quietly served the framing this endpoint removed.
        mockMvc.perform(post("/api/v1/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.detail").value("Acceptable representations: [text/plain]."));

        then(assistant).should(never()).stream(anyString(), any(ChatRequest.class));
    }

    @Test
    void echoesTheConversationHeaderBeforeTheStreamOpens() throws Exception {
        // The header has to be on the response before the first frame flushes; a client cannot read
        // trailers, so an id delivered late is an id never delivered.
        given(assistant.stream(anyString(), any(ChatRequest.class))).willReturn(Flux.just("ok"));

        mockMvc.perform(post("/api/v1/stream")
                        .header(CONVERSATION_ID_HEADER, "user-7:session-4")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(header().string(CONVERSATION_ID_HEADER, "user-7:session-4"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
    }

    @Test
    void startsAFreshConversationWhenTheStreamCarriesNoHeader() throws Exception {
        given(assistant.stream(anyString(), any(ChatRequest.class))).willReturn(Flux.just("Hello."));

        String issued = mockMvc.perform(post("/api/v1/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andReturn().getResponse().getHeader(CONVERSATION_ID_HEADER);

        assertThat(issued).isNotBlank().isNotEqualTo("default");
        then(assistant).should().stream(eq(issued), any(ChatRequest.class));
    }

    @Test
    void abortsTheResponseWhenTheUpstreamStreamFails() throws Exception {
        // Once the response is committed the RFC 9457 mapping cannot apply — the status and headers
        // are already sent — and a plain-text body has no vocabulary for an in-band failure. So the
        // error is propagated and the response aborted: whatever was produced before it stands, and
        // the missing terminating chunk is what tells a client the answer is incomplete.
        given(assistant.stream(anyString(), any(ChatRequest.class)))
                .willReturn(Flux.concat(Flux.just("I found "), Flux.error(
                        new McpTransportException("stream closed while awaiting the tool result"))));

        var started = mockMvc.perform(post("/api/v1/stream")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)).andReturn();

        // The advice declines a committed response, so the failure stays unresolved and surfaces
        // as the aborted dispatch a container turns into a truncated body.
        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(started)))
                .rootCause().isInstanceOf(McpTransportException.class);

        // What had already been written survives, and no problem detail was appended to it: a body
        // of streamed text with JSON glued on the end would parse as neither.
        assertThat(started.getResponse().getContentAsString())
                .isEqualTo("I found ")
                .doesNotContain("stream closed while awaiting the tool result")
                .doesNotContain("The Solr assistant is temporarily unavailable. Please retry.");
    }

    @Test
    void rejectsABlankMessageBeforeOpeningTheStream() throws Exception {
        // Validation fails before the handler runs, so nothing is committed yet and this stays a
        // normal RFC 9457 400 rather than a 200 whose first frame is an error.
        mockMvc.perform(post("/api/v1/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("message must not be blank"));

        then(assistant).should(never()).stream(anyString(), any(ChatRequest.class));
    }

    @Test
    void forgetsAConversation() throws Exception {
        mockMvc.perform(delete("/api/v1/chat/{conversationId}", "user-7:session-4"))
                .andExpect(status().isNoContent());

        then(assistant).should().forget("user-7:session-4");
    }

    /**
     * Drives a streaming request to completion and returns the whole SSE body. MockMvc dispatches
     * the reactive return value asynchronously, so without the second dispatch the recorded
     * response holds only the headers.
     */
    private String streamBody(MockHttpServletRequestBuilder builder) throws Exception {
        var started = mockMvc.perform(builder).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder chat(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY);
    }

    private String issuedConversationId() throws Exception {
        return requireNonNull(mockMvc.perform(chat("/api/v1/chat"))
                .andReturn().getResponse().getHeader(CONVERSATION_ID_HEADER),
                CONVERSATION_ID_HEADER + " should be echoed on every response");
    }
}
