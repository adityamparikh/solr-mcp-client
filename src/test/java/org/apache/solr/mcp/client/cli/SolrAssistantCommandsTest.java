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
package org.apache.solr.mcp.client.cli;

import org.apache.solr.mcp.client.assistant.SolrAssistant;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatReply;
import org.apache.solr.mcp.client.assistant.SolrAssistant.ChatRequest;
import org.apache.solr.mcp.client.assistant.SolrAssistant.EmptyAnswerException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SolrAssistantCommandsTest {

    private final SolrAssistant assistant = mock(SolrAssistant.class);
    private final SolrAssistantCommands commands = new SolrAssistantCommands(assistant);

    @Test
    void passesTheQuestionThroughUnchanged() {
        // The question arrives as one quoted argument; the assistant must see it verbatim.
        when(assistant.send(anyString(), eq(new ChatRequest("how many books are there"))))
                .thenReturn(new ChatReply("There are 42 books."));

        String answer = commands.solrMcp("how many books are there");

        assertThat(answer).isEqualTo("There are 42 books.");
    }

    @Test
    void continuesTheSameConversationAcrossAsks() {
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);

        commands.solrMcp("first");
        commands.solrMcp("second");

        verify(assistant, times(2)).send(conversationId.capture(), any(ChatRequest.class));
        assertThat(conversationId.getAllValues().getFirst())
                .isEqualTo(conversationId.getAllValues().getLast());
        // The id is this adapter's own choice of format; asserting it parses keeps it an opaque
        // but well-formed token rather than something a future change could turn into "" by
        // accident. The parse itself is the assertion — fromString throws or returns non-null.
        assertThatCode(() -> UUID.fromString(conversationId.getValue())).doesNotThrowAnyException();
    }

    @Test
    void startsAFreshConversationAndForgetsTheOldOne() {
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);

        commands.solrMcp("remember this");
        String confirmation = commands.startNewConversation();
        commands.solrMcp("what did I say");

        verify(assistant, times(2)).send(conversationId.capture(), any(ChatRequest.class));
        String oldId = conversationId.getAllValues().getFirst();
        String newId = conversationId.getAllValues().getLast();
        // Released eagerly: chat memory is in-process and never evicted on its own, so an
        // abandoned conversation would otherwise be retained for the life of the shell session.
        verify(assistant).forget(oldId);
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(confirmation).isEqualTo("Started a new conversation.");
    }

    @Test
    void answersWithAHintWhenAskedNothing() {
        // ChatRequest declares its message @NotBlank, and no validator runs on the shell path —
        // this guard is what keeps that contract honest, so blank input must never reach the
        // assistant. A bare `ask` binds to "" (the adapter converts an empty token list).
        assertThat(commands.solrMcp("")).contains("Nothing to ask");
        assertThat(commands.solrMcp("   ")).contains("Nothing to ask");

        verifyNoInteractions(assistant);
    }

    @Test
    void reportsAnEmptyModelAnswerInsteadOfFailingTheCommand() {
        // This adapter's mapping of EmptyAnswerException, as the REST facade's is a 502: a line of
        // text, and the session carries on.
        when(assistant.send(anyString(), any(ChatRequest.class)))
                .thenThrow(new EmptyAnswerException("no content for conversation x"));

        assertThat(commands.solrMcp("anything")).contains("The model returned no content");
    }

    @Test
    void reportsAnUpstreamFailureAsTextAndKeepsTheSessionAlive() {
        // Same upstream taxonomy the REST facade maps to 502/504, reported as a line of text. The
        // exception's message must appear in it: console logging is off under the cli profile, so
        // this line is the only diagnostics the operator gets.
        when(assistant.send(anyString(), any(ChatRequest.class)))
                .thenThrow(new ResourceAccessException("Connection refused to solr-mcp"));

        String answer = commands.solrMcp("anything");

        assertThat(answer)
                .contains("failed upstream")
                .contains("Connection refused to solr-mcp");

        // The session carries on: the next ask still reaches the assistant.
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        assertThat(commands.solrMcp("again")).isEqualTo("ok");
    }
}
