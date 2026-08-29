package org.apache.solr.mcp.client.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * The Solr assistant, expressed independently of any transport.
 *
 * <p>This is the seam a user interface binds to. The REST facade in
 * {@code org.apache.solr.mcp.client.web} is one adapter over it; an in-process UI layer (Vaadin,
 * Hilla, a CLI runner) injects this bean directly rather than calling the application's own HTTP
 * endpoints. Keeping the chat client, memory scoping and MCP tools here means adding a UI
 * duplicates none of that wiring.
 */
@Service
public class SolrAssistant {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public SolrAssistant(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    /**
     * Answers {@code message} in the context of {@code conversationId}, calling Solr MCP tools as
     * the model requires them.
     */
    public String ask(String conversationId, String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * Drops the retained turns for a conversation. Chat memory is held in process and never evicted
     * on its own, so callers must release conversations they are finished with.
     */
    public void forget(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
