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
package org.apache.solr.mcp.client.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolrAssistantTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatMemory chatMemory = mock(ChatMemory.class);
    private final SolrAssistant assistant = new SolrAssistant(chatClient, chatMemory);

    @Test
    void scopesThePromptToTheGivenConversation() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user("Find documents about SolrCloud")).thenReturn(requestSpec);
        doAnswer(invocation -> {
            invocation.<Consumer<ChatClient.AdvisorSpec>>getArgument(0).accept(advisorSpec);
            return requestSpec;
        }).when(requestSpec).advisors(any(Consumer.class));
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("I found 3 documents.");

        SolrAssistant.ChatReply reply = assistant.send("user-7:session-4",
                new SolrAssistant.ChatRequest("Find documents about SolrCloud"));

        assertThat(reply.content()).isEqualTo("I found 3 documents.");
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "user-7:session-4");
    }

    @Test
    void rejectsAModelResponseThatCarriesNoContent() {
        // ChatClient declares content() @Nullable and reports "completed without content" that way.
        // Returning it would break this method's own non-null contract under @NullMarked and hand a
        // caller a null answer in place of the upstream failure it actually is.
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(null);

        assertThatExceptionOfType(SolrAssistant.EmptyAnswerException.class)
                .isThrownBy(() -> assistant.send("user-7:session-4",
                        new SolrAssistant.ChatRequest("Find documents about SolrCloud")))
                .withMessageContaining("user-7:session-4");
    }

    @Test
    void clearsTheConversationFromChatMemory() {
        assistant.forget("user-7:session-4");

        verify(chatMemory).clear("user-7:session-4");
    }

    @Test
    void scopesTheStreamToTheGivenConversation() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user("Find documents about SolrCloud")).thenReturn(requestSpec);
        doAnswer(invocation -> {
            invocation.<Consumer<ChatClient.AdvisorSpec>>getArgument(0).accept(advisorSpec);
            return requestSpec;
        }).when(requestSpec).advisors(any(Consumer.class));
        when(requestSpec.stream()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(Flux.just("I found ", "3 ", "documents."));

        List<String> deltas = assistant.stream("user-7:session-4",
                new SolrAssistant.ChatRequest("Find documents about SolrCloud")).collectList().block();

        // Order is the whole point of a stream: the deltas must arrive as the model produced them,
        // not merely add up to the right answer.
        assertThat(deltas).containsExactly("I found ", "3 ", "documents.");
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "user-7:session-4");
    }

    @Test
    void rejectsAStreamThatCompletesWithoutAnyContent() {
        // The streaming counterpart of the null content() the blocking path rejects: a model that
        // completes having emitted nothing. Both spellings of "no answer" must reach a caller as
        // the same failure, or an adapter would have to recognise two.
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec responseSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(Flux.empty());

        assertThatExceptionOfType(SolrAssistant.EmptyAnswerException.class)
                .isThrownBy(() -> assistant.stream("user-7:session-4",
                        new SolrAssistant.ChatRequest("Find documents about SolrCloud")).blockLast())
                .withMessageContaining("user-7:session-4");
    }

}
