/**
 * Choosing the chat model that drives the assistant.
 *
 * <p>Several provider starters are on the classpath at once. Which one is active is decided from
 * the API keys present in the environment, before Spring AI's provider auto-configurations are
 * evaluated — see {@link org.apache.solr.mcp.client.model.ChatModelProviderSelector}. Nothing here
 * knows about Solr, MCP, or the REST layer; it settles a single question and leaves the model
 * itself to Spring AI.
 */
package org.apache.solr.mcp.client.model;
