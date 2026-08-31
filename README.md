# Solr MCP Client

A Spring Boot REST service that puts a stable HTTP contract in front of a chat model wired to
[Apache Solr MCP](https://github.com/apache/solr-mcp). It is an **MCP client**: it connects to a
separate Solr MCP server, attaches that server's tools to a Spring AI `ChatClient`, and exposes the
resulting assistant over a small JSON API — so a user interface does not have to embed Spring AI, an
MCP SDK, or a model provider's credentials.

```
   HTTP client                this application                    Solr MCP server            Solr
  ─────────────  ──▶  ┌──────────────────────────────┐  ──▶  ┌──────────────────┐  ──▶  ┌─────────┐
  POST /api/v1/chat   │ SolrAssistantController      │ stdio │ solr_search      │       │ /select │
                      │   └▶ SolrAssistant           │  or   │ solr_index_...   │       │ /update │
                      │        └▶ ChatClient ──▶ LLM │  HTTP │ ...              │       │ ...     │
                      └──────────────────────────────┘       └──────────────────┘       └─────────┘
```

**Profiles select how this client *reaches* the Solr MCP server, never how it serves its own API** —
which is why they are all prefixed `mcp-`. It is the same REST service on the same port in every
profile.

| | |
| --- | --- |
| Java toolchain | 25 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| springdoc-openapi | 3.1.0 |
| Build | Gradle (Kotlin DSL) + version catalog |
| HTTP port | **9090** (`server.port`) |
| License | Apache-2.0 |

---

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Build and run](#build-and-run)
- [REST API](#rest-api)
- [Reaching the Solr MCP server: the three profiles](#reaching-the-solr-mcp-server-the-three-profiles)
- [Model provider configuration](#model-provider-configuration)
- [Security posture](#security-posture)
- [Architecture](#architecture)
- [Build and test](#build-and-test)
- [License](#license)

---

## Requirements

- **JDK 25** (the Gradle toolchain will resolve one if it is not the default JDK).
- **An API key for the chat model provider on the classpath.** Out of the box that is Anthropic, so
  `ANTHROPIC_API_KEY`. See [Model provider configuration](#model-provider-configuration) to change it.
  Note the application starts *without* a key and fails on the first chat request instead.
- **A reachable Solr MCP server**, in one of the three shapes below. The application refuses to start
  if it cannot list any tools from one.
- Docker, for the `mcp-stdio-docker` profile only.

---

## Quick start

The default profile is `mcp-stdio` (`spring.profiles.default`), which launches a local Solr MCP
server jar as a child process:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export SOLR_MCP_JAR=/absolute/path/to/solr-mcp.jar   # must be absolute
export SOLR_URL=http://localhost:8983/solr/

./gradlew bootRun
```

Then:

```bash
curl -si localhost:9090/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"How many documents are in the books collection?"}'
```

Or open Swagger UI at <http://localhost:9090/swagger-ui.html>.

---

## Build and run

```bash
./gradlew build                 # compile + test + 80% instruction coverage gate
./gradlew bootRun               # run with the default profile (mcp-stdio)

# pick a different outbound transport
./gradlew bootRun --args='--spring.profiles.active=mcp-stdio-docker'
./gradlew bootRun --args='--spring.profiles.active=mcp-http'

# or run the packaged jar
./gradlew bootJar
java -jar build/libs/solr-mcp-client-0.0.1-SNAPSHOT.jar
java -jar build/libs/solr-mcp-client-0.0.1-SNAPSHOT.jar --spring.profiles.active=mcp-http
```

`mcp-stdio` is a **default** profile, not an active one: naming any profile explicitly *replaces*
it. There is no "no transport" fallback — see [Startup verification](#startup-verification).

---

## REST API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/chat` | Ask the assistant a question |
| `DELETE` | `/api/v1/chat/{conversationId}` | Release a conversation's retained turns |
| `GET` | `/api-docs` | OpenAPI 3 document |
| `GET` | `/swagger-ui.html` | Swagger UI |
| `GET` | `/actuator/health`, `/actuator/info` | The only exposed actuator endpoints |

### `POST /api/v1/chat`

**Request** — `application/json`:

```json
{ "message": "How many documents are in the books collection?" }
```

`message` must be non-blank and at most **8,000** characters.

**Response** — `200 application/json`:

```json
{ "content": "The books collection contains 12,438 documents." }
```

The conversation id travels **only** in the `X-AI-Conversation-Id` header, in both directions —
never in the body. Omit it on the request to start a new conversation; the id used is always echoed
on the response. It is limited to 128 characters.

```bash
curl -si localhost:9090/api/v1/chat \
  -H 'Content-Type: application/json' \
  -H 'X-AI-Conversation-Id: user-7:session-4' \
  -d '{"message":"Now show me the five newest ones."}'
```

There is deliberately no shared default conversation id: this facade performs no inbound
authentication, so a fixed fallback would drop unrelated callers into one memory bucket where they
could read each other's turns. A conversation id is a routing key, not a secret — any caller that
knows one can continue it.

### `DELETE /api/v1/chat/{conversationId}`

Returns `204 No Content`. Chat memory is Spring AI's in-process `MessageWindowChatMemory`: it is
lost on restart and is never evicted on its own, so long-lived deployments should release
conversations when a session ends. For durable history, add
`spring-ai-starter-model-chat-memory-repository-jdbc`.

### Errors

Failures are RFC 9457 `application/problem+json`:

| Status | When |
| --- | --- |
| `400` | Request validation failed, or the API version segment is unsupported/unparseable |
| `404` | No handler — including `/api/chat` with the version segment omitted |
| `502` | The chat model, the MCP server, or the token endpoint rejected the request |
| `504` | An upstream I/O timeout, or a transport that dropped mid-call |

`502`/`504` carry a **generic** `detail` and the cause is logged server-side only: provider messages
routinely embed endpoint URLs and payloads that must not reach an API caller.

### Versioning

The version is a first-class mapping dimension using Spring Framework 7's framework-owned API
versioning, not a hand-written path prefix. Handlers declare `version = "v1"`; the resolver takes
**path segment 1**:

```yaml
spring:
  mvc:
    apiversion:
      use:
        path-segment: 1
      required: false
      default: "v1"
      detect-supported: true
```

| Request | Result |
| --- | --- |
| `/api/v1/chat`, `/api/1/chat`, `/api/1.0/chat`, `/api/1.0.0/chat` | `200` — versions are compared semantically, so all four are the same version |
| `/api/v9/chat` | `400` — unsupported version, named as such |
| `/api/banana/chat` | `400` — unparseable version |
| `/api/chat` | **`404`** — see the gotcha below |

> **Gotcha: `v1` being the *default version* does not make the segment optional.** The version
> segment is part of the URL template (`@RequestMapping("/api/{version}")`), so `/api/chat` never
> reaches a handler at all — it is a 404, not a 400 about the version. `spring.mvc.apiversion.default`
> fires only when the resolver returns `null`, and a path-segment resolver returns whatever sits at
> that index. Serving unversioned URLs would require mapping the controller at both `/api` and
> `/api/{version}` plus an `ApiVersionResolver` bean using `PathApiVersionResolver`'s predicate
> constructor, which Spring Boot does not expose as a property.
>
> `required` must be `false` here: Spring refuses to start with `required: true` and a default
> configured together.

Adding v2 later means adding handlers with `version = "2.0"` — no new controller path, no routing
changes. `version = "1.0+"` carries a handler forward to later versions.

### Cross-origin clients

Cross-origin access is **off** until you name the origins; the intended deployment is same-origin.

```bash
export SOLR_MCP_CLIENT_CORS_ALLOWED_ORIGINS=https://solr-ui.example.com
# or several: https://solr-ui.example.com,https://admin.example.com
```

That maps to `solr.mcp.client.cors.allowed-origins`. When it is set, `ApiCorsConfiguration`
registers a mapping on `/api/**` allowing `GET`, `POST`, `DELETE`, any request header, and — this is
the part that matters — puts `X-AI-Conversation-Id` in `Access-Control-Expose-Headers`. A browser
cannot read a response header cross-origin otherwise, so a UI on another origin would silently see
no id and start a fresh conversation on every request, with no error to notice.

Origins are stated explicitly on purpose: an unqualified `addMapping` allows *every* origin, which
matters especially here because the facade has no inbound authentication.

### OpenAPI

- Document: <http://localhost:9090/api-docs>
- Swagger UI: <http://localhost:9090/swagger-ui.html>

Bean Validation constraints on the request model are emitted into the schema, so generated clients
see the same limits the server enforces. **No security scheme is declared**, because none is
enforced — advertising one would misrepresent the contract.

---

## Reaching the Solr MCP server: the three profiles

| Profile | Transport | The Solr MCP server is… | Use it when |
| --- | --- | --- | --- |
| `mcp-stdio` *(default)* | stdio (JSON-RPC over the child's stdin/stdout) | a jar this process launches | developing locally against a built server jar |
| `mcp-stdio-docker` | stdio | a container this process launches | you have an image but no jar, or want the server isolated |
| `mcp-http` | Streamable HTTP + OAuth2 client credentials | deployed independently | the server is a shared/remote service |

MCP client settings common to all three (`application.yml`): client name `solr-mcp-client`,
`type: SYNC`, `request-timeout: 60s`, `initialized: true`.

### `mcp-stdio` (default)

The client is also the server's **launcher**, so it owns the child process spec.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SOLR_MCP_JAR` | **yes** | — | Absolute path to the Solr MCP server jar |
| `SOLR_URL` | no | `http://localhost:8983/solr/` | Passed to the child; the *server's* setting |
| `SOLR_MCP_COMMAND` | no | `java` | JVM used to launch the server |

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export SOLR_MCP_JAR=/absolute/path/to/solr-mcp.jar
export SOLR_URL=http://localhost:8983/solr/

./gradlew bootRun
```

**`SOLR_MCP_JAR` must be an absolute path.** The child is launched relative to *this* process's
working directory, which differs between `bootRun` and `java -jar`.

**Why this profile knows about Solr at all.** Nothing in this application talks to Solr directly —
`SOLR_URL` is the server's setting. It has to be declared in the YAML because the MCP Java SDK does
**not** pass the parent environment through to the child: it inherits only an allowlist —
`PATH`, `HOME`, `HOMEDRIVE`, `HOMEPATH`, `USERNAME` — so a `SOLR_URL` exported in your shell would
silently never reach the server. (For the same reason, an absolute path in `SOLR_MCP_COMMAND` is
safer than a bare `java`: the child has to resolve it from the inherited `PATH`.)

The server's own Spring profile is deliberately *not* passed — it defaults to `stdio` on its own.
`SOLR_URL` is therefore the single server-owned key this repository hardcodes. To move that
ownership out of the codebase entirely, point `spring.ai.mcp.client.stdio.servers-configuration` at
an operator-owned JSON file describing the launch. Note that inline `connections` entries win over
file entries with the same key, so the profile's `solr-mcp` connection must not be active when you
do.

### `mcp-stdio-docker`

Same stdio transport, container instead of a jar. The server publishes no registry image yet, so
build one **in your Solr MCP checkout** first:

```bash
cd <solr-mcp checkout>
./gradlew jibDockerBuild        # produces solr-mcp:latest
```

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SOLR_MCP_IMAGE` | no | `solr-mcp:latest` | Image to run |
| `SOLR_URL` | no | `http://host.docker.internal:8983/solr/` | Passed as a `-e` flag |
| `SOLR_MCP_DOCKER_COMMAND` | no | `docker` | The container CLI |

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./gradlew bootRun --args='--spring.profiles.active=mcp-stdio-docker'
```

The AOT-pinned native build, tagged `solr-mcp:latest-native-stdio`, works here too. An absolute path
in `SOLR_MCP_DOCKER_COMMAND` (e.g. `/usr/local/bin/docker`) is safer for the allowlist reason above.

**Why this is a separate profile and not an argv override.** Two differences make a shared launcher
misleading rather than merely verbose:

- **`env:` cannot reach a container.** The MCP Java SDK applies that map to the child *process*,
  which here is the `docker` CLI — not the container. Container settings therefore have to be `-e`
  flags inside `args`; an `env:` block would sit in the configuration looking meaningful while doing
  nothing.
- **`SOLR_URL` needs a different value.** Inside a container `localhost` is the container, so Solr
  on the host is `host.docker.internal`. One profile cannot default that correctly for both
  launchers.

**Why `-i` matters:** it keeps the container's stdin open. Without it the container gets EOF
immediately and the transport dies before the first JSON-RPC message is exchanged.

The profile also passes `--add-host=host.docker.internal:host-gateway`, which defines that name on
Linux and is a harmless no-op on Docker Desktop, so the same configuration works on both.

For a launch neither profile anticipates — podman, extra mounts, a wrapper script — point
`spring.ai.mcp.client.stdio.servers-configuration` at an operator-owned JSON file instead.

### `mcp-http`

Streamable HTTP (the server runs `spring.ai.mcp.server.protocol=stateless`, so SSE is not used),
with a dedicated OAuth2 **client-credentials service token** attached to every outbound request.
An API caller's token is never forwarded.

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `SOLR_MCP_HTTP_URL` | no | `http://localhost:8080` | Base URL of the Solr MCP server |
| `SOLR_MCP_HTTP_ENDPOINT` | no | `/mcp` | Path appended to the base URL |
| `SOLR_MCP_OAUTH_CLIENT_ID` | **yes** | — | Confidential IdP client id |
| `SOLR_MCP_OAUTH_CLIENT_SECRET` | **yes** | — | That client's secret |
| `SOLR_MCP_OAUTH_TOKEN_URI` | no | `http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token` | Token endpoint |
| `SOLR_MCP_OAUTH_SCOPES` | no | *(empty)* | Comma-separated scopes |

The defaults describe the local development stack: Solr MCP on `:8080`, Keycloak on `:8180`. This
application serves on `:9090`, so nothing clashes. The client id and secret have **no defaults** on
purpose — misconfiguration should fail at startup, not on the first request.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export SOLR_MCP_OAUTH_CLIENT_ID=solr-mcp-service
export SOLR_MCP_OAUTH_CLIENT_SECRET=dev-only-not-a-secret

./gradlew bootRun --args='--spring.profiles.active=mcp-http'
```

Against a deployed server, override the rest:

```bash
export SOLR_MCP_HTTP_URL=https://solr-mcp.example.com
export SOLR_MCP_OAUTH_TOKEN_URI=https://idp.example.com/oauth/token
export SOLR_MCP_OAUTH_CLIENT_ID=...
export SOLR_MCP_OAUTH_CLIENT_SECRET=...
export SOLR_MCP_OAUTH_SCOPES=solr-mcp.read
```

Scopes are empty by default because the server authorizes on the **audience** claim, not on scopes,
and Keycloak refuses a scope the client has not been assigned.

#### Getting the Keycloak client secret

The Solr MCP server's `compose.yaml` starts Keycloak and imports a realm containing
`solr-mcp-service` — a confidential client with service accounts enabled, dedicated to
machine-to-machine callers like this one. Its secret is `dev-only-not-a-secret`, committed there on
purpose as a development credential.

For a Keycloak set up some other way, open <http://localhost:8180>, select the `solr-mcp` realm, and
go to **Clients → *your client* → Credentials**. It must be **confidential** (client authentication
enabled) with **Service accounts enabled** — a public client cannot use the `client_credentials`
grant at all. Using a different client's secret produces `401 unauthorized_client` at the token
endpoint, before Solr MCP is contacted.

Do not point this at the server's own resource-server client. The service token must belong to this
application, not to the thing it calls.

#### The audience claim is not optional

The Solr MCP server runs its resource server with `validateAudienceClaim(true)`, so it rejects any
token whose `aud` does not contain its canonical resource URI. Read that URI from the server rather
than assuming it:

```bash
curl -s http://localhost:8080/.well-known/oauth-protected-resource
# {"resource":"http://localhost:8080/mcp","authorization_servers":["http://localhost:8180/realms/solr-mcp"],...}
```

Putting that URI in the token is the identity provider's job, and the two providers this server
documents differ:

| Provider | How `aud` gets the resource URI |
|---|---|
| Keycloak | Ignores the RFC 8707 `resource=` parameter. The client needs an **audience protocol mapper** (`oidc-audience-mapper`) whose *Included Custom Audience* is that URI. |
| Auth0 | Takes `audience=` on the token request. Set `SOLR_MCP_OAUTH_AUDIENCE` to that URI. |

Get it wrong and nothing looks broken until the first call: the token is issued perfectly happily and
the server then refuses it with `401`. `scripts/verify-service-token.sh` catches that before the
application does — it provisions nothing, reads the same `SOLR_MCP_*` variables the application
reads, and ends by calling the server with the token it obtained:

```bash
export SOLR_MCP_OAUTH_CLIENT_SECRET=...
./scripts/verify-service-token.sh
```

Creating the client itself belongs to whoever operates the identity provider; see that server's
`docs/security/keycloak.md` or `docs/security/auth0.md`. What this application needs from them is
only the three values above.

#### How the token is wired

The token is applied by
[`mcp-client-security`](https://github.com/spring-ai-community/mcp-security), the client-side half of
the library that secures the server. Two deliberate deviations from its defaults live in
`McpHttpOAuth2Configuration`:

- Its auto-configuration defaults a pre-registered client to the **authorization-code** customizer,
  which resolves a token from an authenticated user. This application has no user — it calls Solr
  MCP on its own behalf, including at startup — so a `McpClientCustomizer` bean supplying the
  **client-credentials** customizer replaces it. (Declaring it as `McpClientCustomizer` rather than a
  bare request customizer is what suppresses the default, because that is the type the
  auto-configuration guards with `@ConditionalOnMissingBean`.)
- The token is fetched with `AuthorizedClientServiceOAuth2AuthorizedClientManager`, not Spring
  Security's request-scoped default. The default resolves the authorized client from the current
  `HttpServletRequest` and fails with *"servletRequest cannot be null"* whenever the MCP client talks
  to the server off a request thread — which includes client initialization at startup.

**Dynamic client registration is explicitly disabled** (`spring.ai.mcp.client.authorization.dynamic-client-registration.enabled: false`).
This client is pre-registered with the IdP; with DCR on it would try to register *itself* on the
first `401`. It is stated rather than left to the default because `mcp-client-security` is a `0.1.x`
library whose README documents the opposite default from its code.

### Startup verification

`McpToolVerifier` runs at startup and **lists the Solr MCP server's tools**. If the list is empty it
throws `IllegalStateException` and the application does not start, naming the three profiles. That
one check covers both ways this goes wrong:

- no MCP connection is configured at all, or
- the connected process is not the Apache Solr MCP server — a stale `SOLR_MCP_JAR`, a
  `SOLR_MCP_HTTP_URL` pointing somewhere else.

Either otherwise produces a healthy-looking application that fails only as unhelpful model answers,
because the assistant has nothing to call.

On success it logs the count and the sorted tool names, e.g.
`Solr MCP server exposes 9 tools: [solr_search, solr_index_document, ...]`.

The check stops at *"is the list non-empty"* and deliberately does **not** assert that particular
tools are present: tool names carry a per-connection prefix from Spring AI's
`McpToolNamePrefixGenerator`, so a list of expected names would pin this client to a naming scheme
neither it nor the server owns, and would need revising whenever the server adds or renames a tool.
What an operator actually needs is the names the server exposes — which is why the successful path
logs them.

Verification is skipped when `spring.ai.mcp.client.initialized=false`: the clients have been told
not to connect, so there is nothing to list and nothing to conclude from silence. (Tests use this to
bind the transport without spawning a child process or opening a socket.)

---

## Model provider configuration

### How the provider is chosen

**Purely by what is on the classpath.** There is no provider-selection code in this application —
`SolrAssistant` depends on `ChatClient`, and `ChatClientConfiguration` builds it from the
auto-configured `ChatClient.Builder`. No provider-specific `ChatModel` type is imported anywhere in
`src/main/java`.

Spring AI's per-provider auto-configurations are each guarded with
`@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "<provider>", matchIfMissing = true)`,
so a starter that is present auto-configures itself unless told otherwise. **Keep exactly one
`spring-ai-starter-model-*` starter on the classpath** — two would auto-configure two `ChatModel`
beans and make `ChatClient.Builder` ambiguous. (`spring.ai.model.chat` can pin one explicitly, or
`none` can disable chat entirely, as the web slice tests do.)

`build.gradle.kts` currently has Anthropic active and OpenAI commented out:

```kotlin
dependencies {
    // ...
    //  implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.anthropic)
}
```

> **Spring AI 2.0 flattened the `.options.` segment.** The canonical model key is
> `spring.ai.<provider>.chat.model`; the older `spring.ai.<provider>.chat.options.model` still binds
> but is deprecated. Prefer the flat form, and be wary of copying snippets written for 1.x.

### A missing API key fails on the first request, not at startup

Nothing checks that you supplied one. `AnthropicChatAutoConfiguration` guards the key with a **null
check, not a blank check** — `if (connectionProperties.getApiKey() != null) builder.apiKey(...)` —
so any value, empty included, is applied as-is and the context starts. A missing or wrong key
therefore surfaces as a provider authentication failure on the **first chat request**: a `502` with
the generic `"The Solr assistant could not complete the request."` detail and the real cause in the
server log. If chat fails immediately on an otherwise healthy deployment, check the key first.

That null guard has a second consequence worth knowing. The Anthropic client falls back to reading
`ANTHROPIC_API_KEY`, then `ANTHROPIC_AUTH_TOKEN`, from the environment itself — but **only when no
key was configured at all**. Since `api-key: ${ANTHROPIC_API_KEY:}` binds an empty string when the
variable is unset, that empty string counts as "configured" and suppresses the fallback. In
practice:

| | Result |
| --- | --- |
| `ANTHROPIC_API_KEY` set | Works — the placeholder carries it into `spring.ai.anthropic.api-key` |
| `ANTHROPIC_AUTH_TOKEN` set, `ANTHROPIC_API_KEY` unset | **Does not work** — the empty binding wins before the SDK's fallback is reached |
| Neither set | Starts; fails on the first chat request |

To use an auth token instead of an API key, bind it explicitly —
`SPRING_AI_ANTHROPIC_API_KEY=$ANTHROPIC_AUTH_TOKEN`.

The empty defaults are deliberate: an unresolvable `${...}` placeholder breaks *every* test that
starts a context whenever the variable is absent, including contexts that never build a `ChatModel`.
That is a worse failure mode than a late auth error. They also keep the block for whichever starter
is *not* on the classpath inert, so a missing key for the inactive provider cannot fail anything.

All of this is the opposite of how the MCP side behaves: `McpToolVerifier` refuses to start without
a working connection, and `SOLR_MCP_OAUTH_CLIENT_ID`/`_SECRET` have no defaults at all.

### Anthropic (the current default)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export ANTHROPIC_MODEL=claude-sonnet-5    # optional; this is the default in application.yml
```

| | Property | Env var used here | Default |
| --- | --- | --- | --- |
| API key | `spring.ai.anthropic.api-key` | `ANTHROPIC_API_KEY` | — |
| Model | `spring.ai.anthropic.chat.model` | `ANTHROPIC_MODEL` | `claude-sonnet-5` |
| Base URL | `spring.ai.anthropic.base-url` | — | Anthropic's API |

Other chat options follow the same flat shape: `spring.ai.anthropic.chat.temperature`,
`.max-tokens`, `.top-p`, `.top-k`, `.thinking`, `.tool-choice`, `.stop-sequences`.

> **The API key is a connection-level property, the model is a chat-level one.** This asymmetry is
> easy to get wrong and fails silently. `AnthropicConnectionProperties` uses the prefix
> `spring.ai.anthropic` and owns `api-key` and `base-url`; `AnthropicChatProperties` uses
> `spring.ai.anthropic.chat` and has **no `api-key` field at all**. So
> `spring.ai.anthropic.chat.api-key` binds nothing — if you find it in older configuration, or nest
> the key under `chat:` by symmetry with the OpenAI block, the key is silently discarded. OpenAI is
> the exception, not the rule: it accepts a key at both levels.

### Switching to OpenAI

Flip the two lines in `build.gradle.kts`:

```kotlin
dependencies {
    // ...
    implementation(libs.spring.ai.openai)
    //  implementation(libs.spring.ai.anthropic)
}
```

Then:

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-5-mini             # optional; this is the default in application.yml
```

| | Property | Env var used here | Default |
| --- | --- | --- | --- |
| API key | `spring.ai.openai.api-key` *(or `spring.ai.openai.chat.api-key`)* | `OPENAI_API_KEY` | — |
| Model | `spring.ai.openai.chat.model` | `OPENAI_MODEL` | `gpt-5-mini` |
| Base URL | `spring.ai.openai.base-url` *(or `spring.ai.openai.chat.base-url`)* | — | `https://api.openai.com` |

Unlike Anthropic, the OpenAI starter accepts the key and base URL at *either* level:
`OpenAiCommonProperties` owns the connection-level pair and `OpenAiChatProperties` offers a
per-chat override, with the chat-level value winning when set. Prefer the connection-level form.

### Any other provider

The general recipe — no application code changes:

1. Add the coordinate to `gradle/libs.versions.toml` (the Spring AI BOM supplies the version):

   ```toml
   [libraries]
   spring-ai-ollama = { module = "org.springframework.ai:spring-ai-starter-model-ollama" }
   ```

2. Swap it into `build.gradle.kts` in place of the current starter.
3. Set that provider's connection properties.

Do not guess property names — each provider page in the Spring AI reference documents its own:

- [Chat Models overview](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)
- [Chat Model comparison / provider index](https://docs.spring.io/spring-ai/reference/api/chat/comparison.html)

### OpenAI-compatible endpoints

Many providers and gateways speak the OpenAI Chat Completions API, and Spring AI's OpenAI starter is
the documented way to consume them: keep `spring-ai-starter-model-openai` and override the base URL.
Commonly used this way are **Groq**, **OpenRouter**, **Mistral-compatible gateways**,
**Azure-hosted OpenAI proxies**, and self-hosted routers such as **LiteLLM**, **vLLM** and
**LocalAI**.

```yaml
spring:
  ai:
    openai:
      base-url: https://api.groq.com/openai      # provider's OpenAI-compatible root
      api-key: ${GROQ_API_KEY}
      chat:
        model: llama-3.3-70b-versatile
```

or entirely from the environment:

```bash
export SPRING_AI_OPENAI_BASE_URL=https://api.groq.com/openai
export SPRING_AI_OPENAI_API_KEY=gsk_...
export SPRING_AI_OPENAI_CHAT_MODEL=llama-3.3-70b-versatile
```

Check the target provider's docs for the exact root path it expects (some want the host, some the
`/v1` path), and Spring AI's own
[OpenAI-compatible endpoint notes](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
before assuming the shape.

### Local models

**Ollama** has a first-class starter:

```toml
spring-ai-ollama = { module = "org.springframework.ai:spring-ai-starter-model-ollama" }
```

```bash
ollama pull qwen3:8b        # any model that supports tool calling
```

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen3:8b
```

See the [Ollama chat docs](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) for
model pull-on-start behaviour and the full option list.

**Anything else that serves the OpenAI API locally** — llama.cpp's `llama-server`, vLLM, LM Studio,
LocalAI, Ollama's own `/v1` shim — works through the OpenAI starter with a dummy key, since most
local servers do not check it:

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:8000       # llama-server / vLLM / LM Studio / LocalAI
      api-key: not-needed
      chat:
        model: <whatever the server calls it>
```

### The tool-calling constraint

**This application is useless without tool calling.** The whole point is that the model invokes the
Solr MCP tools attached in `ChatClientConfiguration`. A model or endpoint that does not implement
tool/function calling will start the application perfectly cleanly and then simply never call Solr —
answering from its own knowledge, or apologising, with no error anywhere. When substituting a
provider or a local model, confirm tool-calling support first.

---

## Security posture

### The inbound REST API is unauthenticated by design

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

### OAuth2 secures the *outbound* connection only

The OAuth2 machinery in the `mcp-http` profile obtains a **client-credentials service token** for
this application's own identity and attaches it to outbound Solr MCP requests. It has nothing to do
with authenticating API callers, and a caller's token is never forwarded. The
[`mcp-http` section](#mcp-http) covers the audience-claim requirement and disabled dynamic client
registration in full.

### Secrets and error leakage

`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` and `SOLR_MCP_OAUTH_CLIENT_SECRET` belong in the environment,
never in committed configuration. The OAuth client id and secret have no default, so a
misconfigured `mcp-http` deployment fails at startup rather than on the first request; the model API
keys default to empty and
[fail on the first chat request instead](#a-missing-api-key-fails-on-the-first-request-not-at-startup).

Upstream failures are returned as RFC 9457 problems with a generic detail and logged server-side,
because provider messages routinely carry endpoint URLs and payloads — including, in an
authentication failure, enough of the request to be worth keeping out of a client response.

---

## Architecture

Packaged by capability, with configuration living next to what it configures — no `config`
grab-bag, no layer packages.

| Package | Role |
| --- | --- |
| `assistant` | `SolrAssistant` (the transport-independent seam) and its `ChatClient` wiring |
| `mcp` | Everything about *reaching* the Solr MCP server: the outbound OAuth2 service token and the startup tool check |
| `web` | The REST service this application *is*: controller, RFC 9457 error mapping, inbound security posture, CORS, OpenAPI |

`SolrAssistant` is public; the chat client bean and its configuration are package-private. The test
for placement: if replacing the REST facade with an in-process UI would leave a class still needed,
it does not belong in `web`.

### Next step: a pluggable UI layer

`SolrAssistant` is the seam a UI binds to. An in-process UI injects that bean directly rather than
calling this application's own HTTP endpoints.
[spring-ai-vaadin](https://springaicommunity.mintlify.app/projects/incubating/spring-ai-vaadin) is
the intended reference — note it is currently an **example application** rather than a released
library (`0.0.1-SNAPSHOT`, not on Maven Central, still on Spring Boot 3.4.5), so adopting it means
adding `com.vaadin:vaadin-spring-boot-starter` and porting its chat view onto `SolrAssistant`, not
depending on it. A React frontend lives on that project's `hilla` branch.

Two things worth doing at the same time:

- add a streaming variant to `SolrAssistant` (`chatClient.prompt()...stream()`), since chat UIs
  render tokens as they arrive;
- expose it over SSE from the REST facade for out-of-process clients.

---

## Build and test

```bash
./gradlew build     # compiles, tests, and enforces 80% instruction coverage
./gradlew test
./gradlew sonar     # SonarCloud analysis
```

Gradle is the only build system; the Maven build was removed. Dependency versions live in
`gradle/libs.versions.toml` — add dependencies there, not inline. CycloneDX produces an SBOM during
packaging; JaCoCo enforces coverage (excluding the application class) and feeds SonarCloud.

Tests never require a live Solr, MCP server, model account or identity provider: context tests set
`spring.ai.mcp.client.initialized=false` so transports bind without launching a child process or
opening a connection, and web slice tests pin `spring.ai.model.chat` so they do not depend on which
API keys a developer happens to have exported.

See [AGENTS.md](AGENTS.md) for the wiring rules and conventions this codebase holds itself to.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

| File | Purpose |
|------|---------|
| [`LICENSE`](LICENSE) | The full Apache-2.0 text, verbatim. |
| [`NOTICE`](NOTICE) | Required attribution, propagated to everything that redistributes this code. |

Every source file carries the standard ASF header, and both files are packaged into `META-INF/` of
each jar the build produces — the executable jar and the `-plain` jar alike:

```kotlin
tasks.withType<Jar>().configureEach {
    metaInf {
        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
    }
}
```

The rule is applied to every `Jar` task rather than to `bootJar` alone, so the plain jar — and any
sources or javadoc jar added later — cannot ship without them. It copies the repository-root files
rather than holding a second copy, so the two cannot drift.

`NOTICE` is deliberately minimal. It carries only legally required attribution, not a dependency
inventory: everything in it propagates downstream to every consumer, so entries that are not
required are a defect rather than good manners. No dependency here is vendored into the source
tree, and Gradle-resolved dependencies do not earn an entry on their own — so if you add one, check
whether its licence actually obliges you before writing anything in this file.
