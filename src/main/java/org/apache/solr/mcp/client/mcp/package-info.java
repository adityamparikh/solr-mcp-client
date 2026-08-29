/**
 * Reaching the Solr MCP server — this application acting as a client.
 *
 * <p>Two transports are selected by profile, and both names are prefixed {@code mcp-} because they
 * describe the <em>outbound</em> connection, never how this application serves its own API:
 *
 * <ul>
 *   <li>{@code mcp-stdio} (default) launches the Solr MCP server as a child process. Here this
 *       application is also the server's launcher and therefore owns the child's environment.</li>
 *   <li>{@code mcp-http} connects to an independently deployed server over Streamable HTTP,
 *       authenticated with a dedicated OAuth2 client-credentials service token.</li>
 * </ul>
 *
 * <p>A single startup check guards the dependency: the server's tool list must be non-empty, which
 * answers both "is a connection configured" and "is this the right server" at once.
 *
 * <p>The inbound REST facade is {@link org.apache.solr.mcp.client.web}. These packages are
 * independent: swapping the transport changes nothing in {@code web}, and replacing the REST facade
 * with an in-process UI changes nothing here.
 */
package org.apache.solr.mcp.client.mcp;
