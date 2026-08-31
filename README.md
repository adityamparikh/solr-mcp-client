# Solr MCP Client

Spring Boot REST client for [Apache Solr MCP](https://github.com/apache/solr-mcp). It puts a stable
HTTP contract in front of a Spring AI chat model that reaches Solr through MCP tools, so a user
interface does not have to embed Spring AI itself.

- Java 25, Spring Boot 4.1.1, Spring AI 2.0.1, MCP Java SDK 2.0.0
This application is a REST service in **every** profile. Profiles select how it *reaches* Solr MCP,
which is why both are prefixed `mcp-`:

- `mcp-stdio` (default) launches a local Solr MCP server as a child process from a jar
- `mcp-stdio-docker` launches that same server as a container over the same stdio transport
- `mcp-http` connects to a remote Solr MCP over Streamable HTTP, authenticated with an OAuth2
  client-credentials service token

## Requirements

- Java 25
- An API key for one chat model provider — `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`
- A built Solr MCP server for the default `mcp-stdio` profile

## Run with mcp-stdio (default)

`SOLR_MCP_JAR` is required and must be an absolute path: the child process is launched relative to
this process's working directory, which differs between `bootRun` and `java -jar`.

```bash
export OPENAI_API_KEY=...          # or ANTHROPIC_API_KEY
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

The server's profile is not passed at all — it defaults to `stdio` on its own — so `SOLR_URL` is the
single config key of the server's that this repository hardcodes; if the server renames it, this
client breaks quietly. To move that ownership out of the codebase entirely, point
`spring.ai.mcp.client.stdio.servers-configuration` at an operator-owned JSON file describing the
launch. Note that inline `connections` entries win over file entries with the same key, so the
`solr-mcp` connection above must be removed for a file to take effect.

`SOLR_MCP_COMMAND` overrides the JVM used to launch the server (default `java`; an absolute path is
safer, because the MCP SDK filters the child process environment).

## Run with mcp-stdio-docker (local server as a container)

Same stdio transport, container instead of a jar. The server publishes no registry image yet, so
build one in your Solr MCP checkout first:

```bash
./gradlew jibDockerBuild            # produces solr-mcp:latest
```

Then:

```bash
export OPENAI_API_KEY=...          # or ANTHROPIC_API_KEY
./gradlew bootRun --args='--spring.profiles.active=mcp-stdio-docker'
```

`SOLR_URL` defaults to `http://host.docker.internal:8983/solr/` and `SOLR_MCP_IMAGE` to
`solr-mcp:latest`; the AOT-pinned native build, tagged `solr-mcp:latest-native-stdio`, works here
too. `SOLR_MCP_DOCKER_COMMAND` overrides the `docker` executable — an absolute path such as
`/usr/local/bin/docker` is safer, since the MCP SDK hands the child only an allowlisted environment.

### Why this is a profile and not a `SOLR_MCP_ARGS` override

Two differences make a shared launcher misleading rather than merely verbose:

- **`env:` cannot reach a container.** The MCP Java SDK applies that map to the child *process*,
  which here is the `docker` CLI. Container settings therefore have to be `-e` flags inside `args`;
  an `env:` block would sit in the configuration looking meaningful while doing nothing.
- **`SOLR_URL` needs a different value.** Inside a container `localhost` is the container, so Solr
  on the host is `host.docker.internal`. One profile cannot default that correctly for both
  launchers.

The profile passes `--add-host=host.docker.internal:host-gateway`, which defines that name on Linux
and is a harmless no-op on Docker Desktop, so the same configuration works on both.

For a launch neither profile anticipates — podman, extra mounts, a wrapper script — point
`spring.ai.mcp.client.stdio.servers-configuration` at an operator-owned JSON file instead. Inline
`connections` entries win over file entries with the same key, so the profile's `solr-mcp`
connection must not be active when you do.

## Run with mcp-http (remote server)

The `mcp-http` profile obtains a dedicated OAuth2 client-credentials token and attaches it to every
outbound MCP request. It never forwards an API caller's token.

Every connection value defaults to the local development stack — a Solr MCP server on
`http://localhost:8080` and Keycloak on `http://localhost:8180` — so only the secret has to be
exported to run against it:

```bash
export OPENAI_API_KEY=...          # or ANTHROPIC_API_KEY
export SOLR_MCP_OAUTH_CLIENT_SECRET=...
./gradlew bootRun --args='--spring.profiles.active=mcp-http --server.port=8090'
```

`--server.port` matters locally: this application serves its own REST API on 8080 by default, which
is also where the Solr MCP server listens, so the two clash on the same machine.

The token is attached by [`mcp-client-security`](https://github.com/spring-ai-community/mcp-security),
the client-side half of the library that secures the server. Its auto-configuration defaults a
pre-registered client to the authorization-code customizer, which resolves a token from an
authenticated user; this application has no user, so `McpHttpOAuth2Configuration` replaces that with
the client-credentials customizer. Dynamic client registration stays off — the client is
pre-registered with the IdP, and DCR would have it try to register itself on the first 401.

Against a deployed server, override the rest:

```bash
export SOLR_MCP_HTTP_URL=https://solr-mcp.example.com
export SOLR_MCP_OAUTH_TOKEN_URI=https://idp.example.com/oauth/token
export SOLR_MCP_OAUTH_CLIENT_ID=...
export SOLR_MCP_OAUTH_CLIENT_SECRET=...
export SOLR_MCP_OAUTH_SCOPES=solr-mcp.read
```

### The audience claim is not optional

Solr MCP runs its resource server with `validateAudienceClaim(true)`, so it rejects any token whose
`aud` does not contain its canonical resource URI. Read that URI from the server itself rather than
assuming it:

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource
# {"resource":"http://localhost:8080/mcp","authorization_servers":["http://localhost:8180/realms/solr-mcp"],...}
```

Keycloak does not yet honour the RFC 8707 `resource=` parameter, so the claim has to be stamped on
by an **Audience** protocol mapper on the client used here — otherwise the token is issued normally
and then refused with 401:

```bash
KC=http://localhost:8180
ADMIN_TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d client_id=admin-cli -d username=admin -d password=admin -d grant_type=password | jq -r .access_token)
CLIENT_UUID=$(curl -s "$KC/admin/realms/solr-mcp/clients?clientId=solr-mcp-server" \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq -r '.[0].id')

curl -s -X POST "$KC/admin/realms/solr-mcp/clients/$CLIENT_UUID/protocol-mappers/models" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{
        "name": "mcp-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "config": {
          "included.custom.audience": "http://localhost:8080/mcp",
          "access.token.claim": "true",
          "id.token.claim": "false"
        }
      }'
```

The IdP client must also be confidential with service accounts enabled; a public client cannot use
the `client_credentials` grant at all.

The token is fetched with `AuthorizedClientServiceOAuth2AuthorizedClientManager`, not the
request-scoped default: the MCP client also talks to the server outside any HTTP request (during
client initialization at startup), where the request-bound manager cannot produce a token.

## Choosing a model provider

Both the OpenAI and Anthropic starters are on the classpath. Which one drives the assistant is
decided from the API keys present in the environment:

| Environment | Result |
| --- | --- |
| `OPENAI_API_KEY` only | OpenAI |
| `ANTHROPIC_API_KEY` only | Anthropic |
| Both, no explicit choice | **Startup fails**, naming both providers and the property that settles it |
| Neither | No provider is activated; startup fails where a chat model is first needed |
| `spring.ai.model.chat` set | Always respected, whatever keys are present |

Refusing to guess between two configured providers follows
[Embabel](https://github.com/embabel/embabel-agent), which likewise declines to infer a default and
requires the model to be named. Picking by precedence would silently decide which account is billed
and how the assistant behaves, and neither is visible from outside.

To run with both keys exported, name the provider:

```bash
export SPRING_AI_MODEL_CHAT=anthropic
```

Model names default per provider and are overridable with `OPENAI_MODEL` / `ANTHROPIC_MODEL`.

### Staying model-agnostic

No application code names a provider. `SolrAssistant` depends on `ChatClient`, and
`ChatClientConfiguration` on the auto-configured `ChatClient.Builder` — no provider-specific
`ChatModel` type is imported anywhere in `src/main/java`. Provider names appear only in
`ChatModelProviderSelector`'s map of provider to API-key variable, and as per-provider model
defaults in `application.yml`.

Adding a third provider is a starter dependency, one entry in that map, and one default model name.
No application code changes.

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

### Verifying the server is really there

`McpToolVerifier` lists the server's tools at startup and fails if the list is empty. That one check
covers both ways this goes wrong: no MCP connection configured at all, and a connection to something
that is not the Apache Solr MCP server — a stale `SOLR_MCP_JAR`, or a `SOLR_MCP_HTTP_URL` pointing
elsewhere. Either otherwise starts cleanly and fails only as unhelpful answers, because the
assistant has nothing to call.

Optionally name the tools this deployment depends on, and startup fails when any is absent:

```bash
export SOLR_MCP_CLIENT_EXPECTED_TOOLS=solr_search,solr_index_document
```

Tool names may carry a per-connection prefix from Spring AI's `McpToolNamePrefixGenerator`, so the
failure reports the names the server actually exposes — configure the property with those rather
than guessing the prefixed form.

Verification is skipped when `spring.ai.mcp.client.initialized` is false, since clients told not to
connect have nothing to list.

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
