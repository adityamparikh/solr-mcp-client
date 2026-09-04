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
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SolrAssistantCommandsTest {

    private final SolrAssistant assistant = mock(SolrAssistant.class);
    private final StringWriter written = new StringWriter();
    private final Terminal terminal = terminalWriting(written);
    private final SolrAssistantCommands commands = new SolrAssistantCommands(assistant, terminal);

    /**
     * A stub terminal over a real {@link PrintWriter}, rather than a JLine terminal over a captured
     * stream. A real dumb terminal post-processes output asynchronously and lags it by a character,
     * so assertions about what had been written by a given moment would be measuring JLine's
     * buffering rather than this command's. What matters here is the text the command writes and
     * the order it writes it in, which a {@code StringWriter} records exactly.
     */
    private static Terminal terminalWriting(StringWriter sink) {
        Terminal terminal = mock(Terminal.class);
        PrintWriter writer = new PrintWriter(sink);
        when(terminal.writer()).thenReturn(writer);
        doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();
        return terminal;
    }

    private String terminalOutput() {
        return written.toString();
    }

    @Test
    void passesTheQuestionThroughUnchanged() {
        // The question arrives as one quoted argument; the assistant must see it verbatim.
        when(assistant.send(anyString(), eq(new ChatRequest("how many books are there"))))
                .thenReturn(new ChatReply("There are 42 books."));

        String answer = commands.chat("how many books are there");

        assertThat(answer).isEqualTo("There are 42 books.");
    }

    @Test
    void continuesTheSameConversationAcrossAsks() {
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);

        commands.chat("first");
        commands.chat("second");

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

        commands.chat("remember this");
        String confirmation = commands.startNewConversation();
        commands.chat("what did I say");

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
        // assistant. A bare `solr-mcp chat` binds to "" (the adapter converts an empty token
        // list). The hint has to name the command as the group prefixes it, or it sends the
        // operator to a word the shell does not answer to.
        assertThat(commands.chat("")).contains("Nothing to ask").contains("solr-mcp chat");
        assertThat(commands.chat("   ")).contains("Nothing to ask").contains("solr-mcp chat");

        verifyNoInteractions(assistant);
    }

    @Test
    void reportsAnEmptyModelAnswerInsteadOfFailingTheCommand() {
        // This adapter's mapping of EmptyAnswerException, as the REST facade's is a 502: a line of
        // text, and the session carries on.
        when(assistant.send(anyString(), any(ChatRequest.class)))
                .thenThrow(new EmptyAnswerException("no content for conversation x"));

        assertThat(commands.chat("anything")).contains("The model returned no content");
    }

    @Test
    void reportsAnUpstreamFailureAsTextAndKeepsTheSessionAlive() {
        // Same upstream taxonomy the REST facade maps to 502/504, reported as a line of text. The
        // exception's message must appear in it: console logging is off under the cli profile, so
        // this line is the only diagnostics the operator gets.
        when(assistant.send(anyString(), any(ChatRequest.class)))
                .thenThrow(new ResourceAccessException("Connection refused to solr-mcp"));

        String answer = commands.chat("anything");

        assertThat(answer)
                .contains("failed upstream")
                .contains("Connection refused to solr-mcp");

        // The session carries on: the next ask still reaches the assistant.
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        assertThat(commands.chat("again")).isEqualTo("ok");
    }

    @Test
    void writesEachDeltaToTheTerminalBeforeTheNextOneArrives() {
        // The point of the streaming command is that text appears while the answer is still being
        // produced. Asserting only on the final buffer would pass just as well for a command that
        // collected everything and printed it at the end, so this samples the terminal from inside
        // the stream: what had been written when each delta was handed over.
        List<String> writtenSoFar = new ArrayList<>();
        when(assistant.stream(anyString(), any(ChatRequest.class)))
                .thenReturn(Flux.just("I found ", "3 ", "documents.")
                        .doOnNext(delta -> writtenSoFar.add(terminalOutput())));

        commands.stream("how many documents are there");

        assertThat(writtenSoFar).containsExactly("", "I found ", "I found 3 ");
        assertThat(terminalOutput()).contains("I found 3 documents.");
    }

    @Test
    void sharesOneConversationWithTheBlockingCommand() {
        // Both commands are the same shell session, so a follow-up must continue whichever command
        // asked first — and `new` must release the one conversation, not one of two.
        when(assistant.stream(anyString(), any(ChatRequest.class))).thenReturn(Flux.just("ok"));
        when(assistant.send(anyString(), any(ChatRequest.class))).thenReturn(new ChatReply("ok"));
        ArgumentCaptor<String> streamed = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> asked = ArgumentCaptor.forClass(String.class);

        commands.stream("first");
        commands.chat("second");

        verify(assistant).stream(streamed.capture(), any(ChatRequest.class));
        verify(assistant).send(asked.capture(), any(ChatRequest.class));
        assertThat(streamed.getValue()).isEqualTo(asked.getValue());
    }

    @Test
    void reportsAnUpstreamStreamFailureAfterWhateverAlreadyArrived() {
        // A stream can fail having already produced text. The partial answer must stay on screen —
        // it is what the model actually said — with the failure reported after it.
        when(assistant.stream(anyString(), any(ChatRequest.class)))
                .thenReturn(Flux.concat(Flux.just("I found "),
                        Flux.error(new ResourceAccessException("Connection refused to solr-mcp"))));

        commands.stream("how many documents are there");

        assertThat(terminalOutput())
                .contains("I found ")
                .contains("failed upstream")
                .contains("Connection refused to solr-mcp");

        // The session carries on, exactly as on the blocking path.
        when(assistant.stream(anyString(), any(ChatRequest.class))).thenReturn(Flux.just("ok"));
        commands.stream("again");
        assertThat(terminalOutput().stripTrailing()).endsWith("ok");
    }

    @Test
    void reportsAnEmptyStreamedAnswerInsteadOfFailingTheCommand() {
        when(assistant.stream(anyString(), any(ChatRequest.class)))
                .thenReturn(Flux.error(new EmptyAnswerException("no content for conversation x")));

        commands.stream("anything");

        assertThat(terminalOutput()).contains("The model returned no content");
    }

    @Test
    void hintsInsteadOfStreamingABlankQuestion() {
        commands.stream("   ");

        assertThat(terminalOutput()).contains("Nothing to ask").contains("solr-mcp stream");
        verifyNoInteractions(assistant);
    }
}
