# Reaching the Solr MCP server: the three profiles

[← back to the README](../README.md)

| Profile | Transport | The Solr MCP server is… | Use it when |
| --- | --- | --- | --- |
| `mcp-stdio` *(default)* | stdio (JSON-RPC over the child's stdin/stdout) | a jar this process launches | developing locally against a built server jar |
| `mcp-stdio-docker` | stdio | a container this process launches | you have an image but no jar, or want the server isolated |
| `mcp-http` | Streamable HTTP + OAuth2 client credentials | deployed independently | the server is a shared/remote service |

MCP client settings common to all three (`application.yml`): client name `solr-mcp-client`,
`type: SYNC`, `request-timeout: 60s`, `initialized: true`.

## `mcp-stdio` (default)

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

## `mcp-stdio-docker`

Same stdio transport, container instead of a jar. No registry image is published yet — as of
August 2026 neither GHCR nor Docker Hub has one, though the server's release workflow is set up to
push `apache/solr-mcp` to Docker Hub with its first official release; once that lands, point
`SOLR_MCP_IMAGE` at it instead of building. Until then, build the image **in your Solr MCP
checkout** first:

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

## `mcp-http`

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

### Getting the Keycloak client secret

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

### The audience claim is not optional

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

### How the token is wired

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

## Startup verification

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

Verification is **unconditional**: no property turns it off, because an assistant with nothing to
call is never a state worth starting in. One consequence is worth knowing before changing anything
here — listing the tools is what first drives Spring AI's `LifecycleInitializer`, so the check
connects even when `spring.ai.mcp.client.initialized=false`. That property defers the connection,
and verifying necessarily undoes the deferral. A context that must not reach a server therefore has
to replace this bean rather than set that property; the transport wiring tests use `@MockitoBean`
for exactly that.
