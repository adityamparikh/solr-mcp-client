# Security posture

[← back to the README](../README.md)

## The inbound REST API is unauthenticated by design

`InboundSecurityConfiguration` registers a single filter chain — in **every** profile — that:

- permits all requests (`anyRequest().permitAll()`),
- disables CSRF (stateless JSON API, no browser-managed session or cookie credentials),
- sets `SessionCreationPolicy.STATELESS`,
- delegates CORS to `ApiCorsConfiguration`.

**Deploy this service behind the hosting application's security boundary. Never expose it
directly.** The chat endpoint, the actuator endpoints (`/actuator/health`, `/actuator/info`) and the
OpenAPI endpoints are all open on the same terms. `OpenApiConfiguration` deliberately declares no
security scheme, so generated clients are not told about protection that does not exist.

Because there is no inbound authentication, two other decisions follow from it and should not be
"tidied up" independently: there is no shared default conversation id (it would merge unrelated
callers' chat memory), and CORS is off until origins are named explicitly.

## OAuth2 secures the *outbound* connection only

The OAuth2 machinery in the `mcp-http` profile obtains a **client-credentials service token** for
this application's own identity and attaches it to outbound Solr MCP requests. It has nothing to do
with authenticating API callers, and a caller's token is never forwarded. The
[`mcp-http` section of the transports guide](transports.md#mcp-http) covers the audience-claim
requirement and disabled dynamic client registration in full.

## Secrets and error leakage

`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` and `SOLR_MCP_OAUTH_CLIENT_SECRET` belong in the environment,
never in committed configuration. The OAuth client id and secret have no default, so a
misconfigured `mcp-http` deployment fails at startup rather than on the first request; the model API
keys default to empty and
[fail on the first chat request instead](model-providers.md#a-missing-api-key-fails-on-the-first-request-not-at-startup).

Upstream failures are returned as RFC 9457 problems with a generic detail and logged server-side,
because provider messages routinely carry endpoint URLs and payloads — including, in an
authentication failure, enough of the request to be worth keeping out of a client response.
