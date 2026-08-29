# Solr MCP Client

Spring Boot REST client for [Apache Solr MCP](https://github.com/apache/solr-mcp). It puts a stable
HTTP contract in front of a Spring AI chat model that reaches Solr through MCP tools, so a user
interface does not have to embed Spring AI itself.

- Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.0
This application is a REST service in **every** profile. Profiles select how it *reaches* Solr MCP,
which is why both are prefixed `mcp-`:

- `mcp-stdio` (default) launches a local Solr MCP server as a child process
- `mcp-http` connects to a remote Solr MCP over Streamable HTTP, authenticated with an OAuth2
  client-credentials service token

## Requirements

- Java 25
- `OPENAI_API_KEY` — required; the application fails at startup without it
- A built Solr MCP server for the default `mcp-stdio` profile

## Run with mcp-stdio (default)

`SOLR_MCP_JAR` is required and must be an absolute path: the child process is launched relative to
this process's working directory, which differs between `bootRun` and `java -jar`.

```bash
export OPENAI_API_KEY=...
export SOLR_MCP_JAR=/absolute/path/to/solr-mcp.jar
export SOLR_URL=http://localhost:8983/solr/
./gradlew bootRun
```

### Why this profile knows about Solr at all

`SOLR_URL` is the *server's* setting, not this client's — nothing here talks to Solr directly. It
appears in `application-mcp-stdio.yml` because under `mcp-stdio` this process is the server's **launcher**
and owns the child's environment. It has to be declared explicitly: the MCP Java SDK does not pass
the parent environment through, inheriting only an allowlist (`PATH`, `HOME`, `HOMEDRIVE`,
`HOMEPATH`, `USERNAME`), so a `SOLR_URL` exported in your shell would silently never reach the
child. The `mcp-http` profile has no `SOLR_URL` and should never gain one: there the server is deployed
independently and owns its own configuration.

The trade-off is that this repository hardcodes two of the server's config keys (`SOLR_URL`,
`SPRING_PROFILES_ACTIVE`); if the server renames one, this client breaks quietly. To move that
ownership out of the codebase entirely, point
`spring.ai.mcp.client.stdio.servers-configuration` at an operator-owned JSON file describing the
launch. Note that inline `connections` entries win over file entries with the same key, so the
`solr-mcp` connection above must be removed for a file to take effect.

`SOLR_MCP_COMMAND` overrides the JVM used to launch the server (default `java`; an absolute path is
safer, because the MCP SDK filters the child process environment).

## Run with mcp-http (remote server)

The `mcp-http` profile obtains a dedicated OAuth2 client-credentials token and attaches it to every
outbound MCP request. It never forwards an API caller's token.

```bash
export OPENAI_API_KEY=...
export SOLR_MCP_HTTP_URL=https://solr-mcp.example.com
export SOLR_MCP_OAUTH_TOKEN_URI=https://idp.example.com/oauth/token
export SOLR_MCP_OAUTH_CLIENT_ID=...
export SOLR_MCP_OAUTH_CLIENT_SECRET=...
export SOLR_MCP_OAUTH_SCOPES=solr-mcp.read
./gradlew bootRun --args='--spring.profiles.active=mcp-http'
```

Configure the identity provider to issue a JWT whose `aud` is Solr MCP's `/mcp` resource, as that
server's OAuth2 resource server requires.

The token is fetched with `AuthorizedClientServiceOAuth2AuthorizedClientManager`, not the
request-scoped default: the MCP client also talks to the server outside any HTTP request (during
client initialization at startup), where the request-bound manager cannot produce a token.

## REST API

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/chat` | Ask the assistant. Body: `{ "message": "..." }`; conversation via `X-AI-Conversation-Id` |
| `DELETE` | `/api/v1/chat/{conversationId}` | Release a conversation's retained turns |

### Versioning

The API version is a first-class mapping dimension, using Spring Framework 7's built-in API
versioning rather than a hand-written path prefix. Handlers declare `version = "1.0"`; the version
is resolved from the second path segment and is required on every request.

```yaml
spring:
  mvc:
    apiversion:
      use:
        path-segment: 1
      required: true
      detect-supported: true
```

| Request | Result |
| --- | --- |
| `/api/v1/chat`, `/api/1/chat`, `/api/1.0/chat`, `/api/1.0.0/chat` | 200 — one version, compared semantically |
| `/api/v2/chat` | 400 — unsupported version, named as such |
| `/api/banana/chat` | 400 — unparseable version |
| `/api/chat` | 404 — the version segment is part of the mapping |

`spring.mvc.apiversion.default` is deliberately not set. It fires only when the resolver returns
null, and a path-segment resolver always returns whatever sits at that index — so a default would be
config that can never take effect. Spring also refuses to start with `required: true` and a default
configured together (`versionRequired cannot be set to true if a defaultVersion is also
configured`). Serving an unversioned `/api/chat` would need a `WebMvcConfigurer` supplying
`usePathSegment(int, Predicate<RequestPath>)`, which has no property equivalent; requiring the
version keeps the configuration to the four lines above.

Adding v2 later means adding handlers with `version = "2.0"` — no new controller path, no routing
changes. `version = "1.0+"` carries a handler forward to later versions.

### Conversations

The conversation is carried in the **`X-AI-Conversation-Id`** header, in both directions and nowhere
else — it is ambient session context, not part of what the user asked or what the model answered.
Omit it to start a new conversation; the id used is always returned in that header.

There is deliberately no shared default conversation. This facade performs no inbound
authentication, so a fixed fallback id would put unrelated callers into one memory bucket where they
could read each other's turns.

```bash
curl -si localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -H 'X-AI-Conversation-Id: user-7:session-4' \
  -d '{"message":"How many documents are in the books collection?"}'
```

#### Cross-origin clients

A browser cannot read a response header cross-origin unless the server names it in
`Access-Control-Expose-Headers`. Since the header is the only carrier of the conversation, a UI on
another origin would otherwise see no id and start a new conversation on every request — a failure
with no error to notice. `ApiCorsConfiguration` exposes it whenever CORS applies.

Cross-origin access is off until you name the origins; the default deployment is same-origin.

```bash
export SOLR_MCP_CLIENT_CORS_ALLOWED_ORIGINS=https://solr-ui.example.com
```

Origins are stated explicitly rather than left to defaults on purpose: an unqualified CORS mapping
allows every origin, which matters here because the facade has no inbound authentication.

### Verifying the server exposes the tools you expect

`McpConnectionVerifier` proves a connection is *configured*; it cannot tell that the process on the
other end is the Solr MCP server. A stale `SOLR_MCP_JAR`, or a `SOLR_MCP_HTTP_URL` pointing at some
other MCP server, starts cleanly and then fails only as unhelpful answers — the assistant simply has
no Solr tools to call.

Name the tools this deployment depends on and `McpToolVerifier` fails startup when any is absent:

```bash
export SOLR_MCP_CLIENT_EXPECTED_TOOLS=solr_search,solr_index_document
```

This is opt-in because listing tools requires talking to the server. Tool names may carry a
per-connection prefix from Spring AI's `McpToolNamePrefixGenerator`, so the failure message reports
the names the server actually exposes — configure the property with those, rather than guessing the
prefixed form.

A conversation id is a routing key, not a secret: any caller that knows one can continue it.

Chat memory is Spring AI's default in-process `MessageWindowChatMemory`. It is lost on restart and
is never evicted on its own, so long-lived deployments should `DELETE` conversations when a session
ends. For durable history, swap in `spring-ai-starter-model-chat-memory-repository-jdbc`.

### OpenAPI

- OpenAPI document: `/api-docs`
- Swagger UI: `/swagger-ui.html`

Validation constraints on the request model are emitted into the schema, so generated clients see
the same limits the server enforces.

## Security posture

The REST facade is **unauthenticated by design** and performs no inbound authentication. OAuth2 in
the `mcp-http` profile secures the *outbound* MCP connection only. Deploy this service behind your
application's security boundary; do not expose it directly. Actuator (`/actuator/health`,
`/actuator/info`) and the OpenAPI endpoints are open on the same terms.

Secrets (`OPENAI_API_KEY`, `SOLR_MCP_OAUTH_CLIENT_SECRET`) belong in the environment, never in
committed configuration. Upstream failures are reported as RFC 9457 problems with a generic detail
and logged server-side, because provider messages routinely carry endpoint URLs and payloads.

## Next step: a pluggable UI layer

`SolrAssistant` (`org.apache.solr.mcp.client.assistant`) is the transport-independent seam. The REST
controller is one adapter over it; an in-process UI injects that bean directly rather than calling
this application's own HTTP endpoints.

[spring-ai-vaadin](https://springaicommunity.mintlify.app/projects/incubating/spring-ai-vaadin) from
Spring AI Community is the intended reference. Note that it is currently an **example application**
rather than a released library — `0.0.1-SNAPSHOT`, not published to Maven Central, and still on
Spring Boot 3.4.5 — so adopting it means adding `com.vaadin:vaadin-spring-boot-starter` and porting
its chat view onto `SolrAssistant`, not adding a dependency on it. A React frontend is available on
that project's `hilla` branch.

Two things worth doing at the same time:

- Add a streaming variant to `SolrAssistant` (`chatClient.prompt()...stream()`), since chat UIs
  render tokens as they arrive.
- Expose it over SSE from the REST facade for out-of-process clients.

## Build and test

```bash
./gradlew build     # compiles, tests, and enforces 80% instruction coverage
./gradlew sonar     # SonarCloud analysis
```

Gradle is the only build. Dependency versions live in `gradle/libs.versions.toml`. CycloneDX
produces an SBOM during packaging; JaCoCo enforces coverage and feeds SonarCloud.
