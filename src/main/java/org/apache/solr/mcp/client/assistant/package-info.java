/**
 * The Solr assistant, expressed independently of any transport.
 *
 * <p>{@link org.apache.solr.mcp.client.assistant.SolrAssistant} is the seam a user interface binds
 * to. {@link org.apache.solr.mcp.client.web} is one adapter over it; an in-process UI (Vaadin,
 * Hilla, a CLI runner) injects the bean directly rather than calling this application's own HTTP
 * endpoints. Chat client wiring, memory scoping and MCP tool attachment live here so that adding a
 * UI duplicates none of it.
 *
 * <p>Only {@code SolrAssistant} is public; the chat client and its configuration are package
 * private. New assistant behaviour belongs here, not in a controller.
 */
package org.apache.solr.mcp.client.assistant;
