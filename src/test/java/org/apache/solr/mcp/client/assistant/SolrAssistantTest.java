package org.apache.solr.mcp.client.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
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

        String answer = assistant.ask("user-7:session-4", "Find documents about SolrCloud");

        assertThat(answer).isEqualTo("I found 3 documents.");
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "user-7:session-4");
    }

    @Test
    void clearsTheConversationFromChatMemory() {
        assistant.forget("user-7:session-4");

        verify(chatMemory).clear("user-7:session-4");
    }
}
