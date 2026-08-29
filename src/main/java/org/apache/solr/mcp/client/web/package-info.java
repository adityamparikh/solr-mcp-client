/**
 * The REST service this application <em>is</em>.
 *
 * <p>Everything here concerns HTTP that this application <strong>serves</strong>: the versioned
 * controller, RFC 9457 error mapping, the inbound security posture, and the OpenAPI description.
 * The application is a REST service in every profile — profiles choose how it reaches Solr MCP, not
 * whether it has a web layer.
 *
 * <p>Nothing about the <em>outbound</em> connection to Solr MCP belongs here, even when that
 * connection also happens to speak HTTP; that lives in
 * {@link org.apache.solr.mcp.client.mcp}. The distinguishing question is which direction the
 * traffic flows: if replacing this REST facade with an in-process UI would leave a class still
 * needed, the class does not belong in this package.
 *
 * <p>This package is an adapter over {@link org.apache.solr.mcp.client.assistant.SolrAssistant} and
 * holds no business logic.
 */
package org.apache.solr.mcp.client.web;
