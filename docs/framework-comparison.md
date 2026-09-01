# Framework comparison: Spring AI vs JVM and Python agent frameworks

[← back to the README](../README.md)

How this application's MCP client feature set compares to what the other significant JVM agent
frameworks — and the major Python ones — offered as of **August–September 2026**. The baseline is what this repository actually
ships: Spring AI 2.0 on Spring Boot 4.1, three outbound transport profiles
([Transports](transports.md)), OAuth2 client-credentials on the `mcp-http` connection via
[`mcp-client-security`](https://github.com/spring-ai-community/mcp-security), fail-fast startup
tool verification (`McpToolVerifier`), and trace propagation into MCP HTTP calls
([Observability](observability.md)).

Versions assessed — JVM: **LangChain4j 1.19.0** (MCP module `langchain4j-mcp` 1.19.0-beta29),
**Koog 1.2.0**, **Embabel 1.5.1**. Python: **langchain-mcp-adapters 0.3.2** (LangChain/LangGraph),
**OpenAI Agents SDK 0.22.0**, **PydanticAI 2.37.0**, **Google ADK 2.8.0**, **CrewAI 1.15.18**.
All of these move quickly; re-verify before acting on this.

## TL;DR

**None of the JVM alternatives beats this stack for this specific application.** The two
hardest-won features here — spec-compliant OAuth2 client-credentials on the MCP connection and
property-driven transport profiles — are exactly where all three JVM alternatives are weakest.
Embabel is the closest (its MCP client *is* Spring AI's, inherited verbatim), LangChain4j reaches
parity only on Quarkus, and Koog requires the most hand-written glue.

The Python ecosystem partially inverts that story: spec-compliant OAuth comes nearly free from the
official MCP Python SDK's `httpx` auth providers (LangChain and the OpenAI Agents SDK expose them
directly), and PydanticAI's trace propagation into MCP calls is better than what this repository
had to hand-build — but no Python framework matches Spring Boot's declarative profile-driven
configuration or fail-fast startup semantics, and CrewAI/ADK are behind on MCP auth.

## JVM parity matrix

| Feature (as built here) | Spring AI 2.0 (this app) | LangChain4j 1.19 | Koog 1.2 | Embabel 1.5.1 |
| --- | --- | --- | --- | --- |
| stdio transport (jar child process) | ✅ starter-managed | ✅ `StdioMcpTransport` (command/env builder) | ⚠️ caller launches the `Process` | ✅ inherited from Spring AI |
| stdio via Docker | ✅ (docker as the command) | ✅ dedicated `DockerMcpTransport` module | ⚠️ DIY `ProcessBuilder("docker", "run", "-i", …)` | ✅ inherited |
| Streamable HTTP (incl. stateless) | ✅ starter | ✅ incl. legacy protocol + version pinning | ✅ since 1.0.0 | ✅ inherited |
| OAuth2 on the MCP connection (MCP auth spec, client credentials) | ✅ `mcp-security` | ❌ core: header suppliers only; ✅ only via Quarkus OIDC | ❌ none — Ktor `Auth` plugin DIY | ⚠️ user-token forwarding pattern; `mcp-security` should compose but is undocumented |
| Declarative YAML config / profiles | ✅ `spring.ai.mcp.client.*` | ❌ on Spring (no MCP starter); ✅ on Quarkus | ❌ programmatic Kotlin only | ✅ inherited, plus tool-group YAML |
| Fail-fast startup verification | ✅ `McpToolVerifier` | ❌ core has none (Quarkus adds health checks) | ✅ eager connect fails fast by construction | ❌ deliberately opposite: catches init failures and starts anyway |
| Tool filtering / name-collision handling | ⚠️ prefix generator only | ✅ ahead: filters, name mappers, per-server predicates | ❌ merge registries by hand, no prefixing | ✅ ahead: ToolGroup roles, filters, permissions |
| Chat memory | ✅ advisor + conversation id | ✅ mature `ChatMemory` / `@MemoryId` | ✅ history compression, checkpoints | ✅ own Conversation model + persistent stores |
| Trace propagation into MCP calls | ✅ custom customizer | ❌ DIY via header supplier | ❌ DIY via Ktor interceptor (has MCP tool-call *spans*) | ⚠️ likely via Micrometer, undocumented |
| Resources / prompts / sampling / elicitation | ✅ Spring AI handlers | ⚠️ resources+prompts strong ("resources as tools"), no sampling/elicitation | ❌ tools only; rest through the raw Kotlin SDK client | ✅ inherited handlers; only tools surfaced to agents |
| Maturity of MCP support | GA | core GA, MCP module still beta-versioned | stable 1.x, MCP gaps open | GA, fast cadence, tracks Spring AI 2.0 since 1.5.0 |

## LangChain4j

Strongest pure-protocol MCP client of the three, and genuinely ahead of Spring AI on tool curation
— `McpToolProvider` supports `.filterToolNames(...)`, a `BiPredicate<McpClient, ToolSpecification>`
filter, a `toolNameMapper` for renaming/prefixing across servers, and
`.failIfOneServerFails(boolean)` for partial-failure policy. Its "resources as tools" presenter
(synthetic `list_resources`/`get_resource` tools) is also a nice ergonomic Spring AI lacks. The
transports are complete: stdio with an env-map builder, a first-class `DockerMcpTransport`, and
`StreamableHttpMcpTransport` with protocol auto-detection.

The catch is that its production hardening is **Quarkus-shaped**. OAuth/OIDC wiring
(`quarkus-langchain4j-oidc-mcp-auth-provider`, `oidc-client-name` for service tokens), YAML-driven
MCP config (`quarkus.langchain4j.mcp.*`), health checks, and MCP tracing all live in the Quarkus
extension. The [`langchain4j-spring`](https://github.com/langchain4j/langchain4j-spring) repo has
**no MCP starter at all** — on Spring Boot every transport and client is a hand-built `@Bean`, and
OAuth token acquisition is rebuilt behind an `McpHeadersSupplier` (401-retry semantics were still
an open discussion, issue [#2898](https://github.com/langchain4j/langchain4j/issues/2898)). Core
also has no MCP-auth-spec implementation: no client credentials, no authorization code, no dynamic
client registration, no RFC 8707 resource indicators.

**Verdict:** on this Spring Boot stack, meaningfully behind — the app would lose declarative
config, fail-fast, MCP-call tracing, and spec OAuth. On Quarkus, near parity (behind only on DCR
and resource indicators), and ahead on multi-server tool federation.

## Koog

JetBrains' Kotlin agent framework rides the official Kotlin MCP SDK (`agents-mcp` module wrapping
`io.modelcontextprotocol` kotlin-sdk 0.11.x), so transport coverage is at parity: stdio, SSE, and
Streamable HTTP (primary transport since 1.0.0). It is arguably ahead on the agentic surroundings
— graph workflows, history compression/checkpoints, and multiplatform OpenTelemetry with dedicated
spans for MCP tool calls plus turnkey Langfuse export.

It is furthest behind exactly where this application is strongest:

- **Security:** no MCP authorization-spec support at all — no discovery, no client credentials, no
  DCR, no resource indicators. The maintainers' sanctioned workaround (discussions
  [#717](https://github.com/JetBrains/koog/discussions/717),
  [#940](https://github.com/JetBrains/koog/discussions/940)) is to configure a Ktor `HttpClient`
  with the `Auth` plugin and hand it to the transport — client-credentials refresh is DIY.
- **Configuration:** all MCP wiring is programmatic Kotlin. The Koog Spring Boot starter
  autoconfigures LLM executors only; nothing corresponds to `spring.ai.mcp.client.*`. Each of the
  three profiles here would become hand-written code, including child-process lifecycle
  (`McpToolRegistryProvider.fromProcess(...)` takes an already-started `Process`).
- **Tool management:** no per-server prefixing or filtering — registries are merged by hand.

**Verdict:** parity on protocol, ahead on agent features and MCP-call spans, clearly behind on
security and declarative configuration. The most DIY glue of the three for this use case.

## Embabel

The closest comparison, because since 1.5.0 Embabel runs on exactly this stack — Spring AI 2.0 GA,
Spring Boot 4, Java 21 baseline — and its MCP client **is Spring AI's**: `embabel-agent-api`
depends on `spring-ai-starter-mcp-client`, and its `QuiteMcpClientAutoConfiguration` subclasses
Spring AI's auto-configuration, keeping every transport and every `spring.ai.mcp.client.*`
property. The `mcp-http`/`mcp-stdio` YAML in this repository would carry over unchanged.

What it adds is a governance and orchestration layer:

- **ToolGroups** — role-based tool exposure (`McpToolGroup` with filter lambdas over
  `ToolCallback`), an explicit permission model (e.g. `INTERNET_ACCESS`), and per-action tool
  scoping via the GOAP planner. Genuinely nicer than exposing every MCP tool to the model.
- **Conversation persistence** (`embabel-chat-store`) and an MCP **server** mode with JWT security
  for exposing agents as tools.

Two caveats cut against this application's design:

- **It inverts the fail-fast policy.** `QuiteMcpClientAutoConfiguration` deliberately catches MCP
  client init failures, logs them, and starts with the surviving clients — the exact
  "healthy-looking application with nothing to call" state `McpToolVerifier` exists to prevent.
  A verifier like this one would still be needed on top.
- **OAuth is user-token forwarding, not service tokens.** Embabel's documented pattern defers the
  MCP handshake until a caller's token is in the `SecurityContext`. Nothing documents composing
  `mcp-security`'s client-credentials customizer with it; since the transport hooks are stock
  Spring AI the combination should work, but it is unverified.

**Verdict:** at parity on MCP plumbing by construction; ahead on tool governance, planning, and
conversation persistence; behind/unproven on service-token OAuth2. The only framework of the three
adoptable incrementally without touching this repository's transport or OAuth wiring — at the cost
of adopting its programming model and its Spring AI upgrade cadence.

## Python parity matrix

Same rows, same baseline. One structural difference matters up front: the official **MCP Python
SDK** ships spec-compliant OAuth clients (`OAuthClientProvider` with authorization code + PKCE,
dynamic client registration, RFC 8707 resource indicators, and a `ClientCredentialsOAuthProvider`)
as pluggable `httpx.Auth` objects — so any Python framework that exposes an `auth`/`http_client`
hook gets what `mcp-security` provides on the JVM almost for free.

| Feature (as built here) | LangChain / LangGraph (adapters 0.3.2) | OpenAI Agents SDK 0.22 | PydanticAI 2.37 | Google ADK 2.8 | CrewAI 1.15 |
| --- | --- | --- | --- | --- | --- |
| stdio transport (incl. docker as command) | ✅ `MultiServerMCPClient` command/args/env | ✅ `MCPServerStdio` | ✅ `StdioTransport` (FastMCP) | ✅ `StdioConnectionParams` (banned in declarative configs since 2.7) | ✅ `StdioServerParameters` / `MCPServerStdio` |
| Streamable HTTP (incl. stateless) | ✅ stateless per call by default | ✅ `MCPServerStreamableHttp` | ✅ default for URLs | ✅ + session pool (2.8) | ✅ default; persistent session |
| OAuth2 on MCP connection (MCP auth spec, client credentials) | ✅ `auth` field takes the SDK's OAuth providers incl. client credentials | ⚠️ `auth` passthrough works but is undocumented | ⚠️ full interactive OAuth (`'oauth'`, DCR); client credentials DIY via `httpx.Auth` | ❌ headers reliable; spec OAuth has open defects (issues #2168, #3331, #3449) | ❌ headers-only in OSS; OAuth only in the paid AMP platform |
| Declarative config / profiles | ❌ dict-based (load your own JSON/YAML) | ❌ programmatic only | ⚠️ `load_mcp_toolsets()` reads `mcpServers` JSON with env expansion | ✅ Agent Config YAML incl. MCP toolsets | ⚠️ `mcps` string DSL, but in code; crew YAML carries no MCP wiring |
| Fail-fast startup verification | ❌ DIY | ⚠️ explicit `connect()` — caller controls | ⚠️ implicit lazy lifecycle | ❌ no toggle; bounded session-readiness wait | ⚠️ fail-fast on auth errors; lazy connect otherwise |
| Tool filtering / collision handling | ✅ `tool_name_prefix`, interceptors | ✅ **ahead**: static+dynamic filters, per-server names, approvals, guardrails, retry/backoff | ✅ `.prefixed()`, `.filtered()`, `process_tool_call` | ⚠️ `tool_filter` only, no cross-server prefixing | ✅ filters + DSL prefixing |
| Chat memory | ✅ LangGraph checkpointers/store | ✅ Sessions (SQLite/Redis/custom) | ⚠️ message history, no built-in store | ✅ **ahead**: Session/State/Memory services | ⚠️ crew-run memory, not conversational |
| Trace propagation into MCP calls | ❌ DIY via headers | ✅ `tool_meta_resolver` injects `_meta` | ✅ **ahead**: `traceparent` into `_meta` per OTel GenAI semconv | ❌ spans stop at client (MCP exchanges logged) | ❌ third-party tracing only |
| Resources / prompts / sampling / elicitation | ⚠️ prompts, resources, elicitation; no sampling | ⚠️ prompts + resources only | ✅ all four + task-augmented execution | ⚠️ elicitation only; rest pending (#3449) | ❌ tools only; results flattened to first text block |
| Maturity of MCP support | 0.3.x, de-facto LangGraph standard | 0.x, rapid, well documented | v2 stable, very active | 2.0 GA, MCP surface mid-buildout | stable adapter; new `crewai.mcp` DSL has rough edges |

### Python frameworks in brief

**LangChain / LangGraph** (`langchain-mcp-adapters`) — closest Python analog to this app's
security posture: the connection config's `auth` field accepts the SDK's
`ClientCredentialsOAuthProvider`, giving headless service tokens out of the box, plus DCR and
RFC 8707 — items even `mcp-security` lacks. Tool interceptors and per-server prefixing are strong.
Weak spots: no declarative wiring, no resilience policies, trace propagation is DIY.

**OpenAI Agents SDK** — best-in-class tool governance (static and dynamic filters, human-in-the-loop
approvals, input/output guardrails, retry with exponential backoff, tool-list caching) and a unique
`HostedMCPTool` mode where the Responses API connects to the MCP server server-side. OAuth is a raw
`httpx.Auth` passthrough — achievable but undocumented. Observability is OpenAI-platform-centric
unless you attach OTel processors, though `tool_meta_resolver` propagates trace context cleanly.

**PydanticAI** — the most complete MCP *protocol* client in Python: sampling, elicitation,
resources, server-instruction injection, and task-augmented execution all supported, with OTel
GenAI-semconv tracing that injects `traceparent` into MCP `_meta` — the thing this repository's
`TracePropagatingHttpRequestCustomizer` hand-builds. Its packaged OAuth flow is
browser-interactive (authorization code + PKCE + DCR via FastMCP); a headless client-credentials
flow like this app's means wiring your own `httpx.Auth`. No built-in persistent conversation store.

**Google ADK** — the only Python framework with genuine declarative config (Agent Config YAML
covers MCP toolsets) and the strongest session/memory services, plus Vertex-integrated
observability. But MCP auth is its documented weak point: header injection is the only reliable
path today, spec OAuth has open architectural defects, and resources/sampling/prompts are still
pending on the umbrella tracking issue. The furthest from this app's security posture.

**CrewAI** — transports and resilience knobs (timeouts, backoff retries, fail-fast on auth errors,
tool-list caching) are at parity, and the `mcps` DSL with prefixing/filtering is convenient. But
OSS auth is custom-headers-only — spec OAuth exists only in the commercial AMP platform — MCP
support is tools-only, and tool results are flattened to the first text block. A Keycloak
client-credentials flow would mean manual token fetch/refresh into headers.

## Bottom line

For a secured REST facade over a single MCP server, this implementation is at or above parity
across the board. The realistic reasons to adopt one of the alternatives are orthogonal to MCP
plumbing:

| If the application grows toward… | Consider |
| --- | --- |
| Federating many MCP servers with tool renaming/filtering (JVM) | LangChain4j (ideally on Quarkus) |
| Multi-step Kotlin agent graphs | Koog |
| Multi-agent planning, tool governance, MCP server mode — without re-plumbing | Embabel |
| A Python port keeping client-credentials OAuth on the MCP connection | LangChain/LangGraph (SDK `ClientCredentialsOAuthProvider`) |
| Tool approvals/guardrails, or offloading the MCP connection to OpenAI's platform | OpenAI Agents SDK |
| Fullest MCP protocol coverage + best tracing in Python | PydanticAI |
| Declarative YAML agents and managed sessions on Google Cloud | Google ADK |
| Role-based multi-agent crews where MCP is just a tool source | CrewAI |

The dividing line across every framework surveyed: MCP *protocol* support (transports, tool
listing) is now commodity — everyone rides an official SDK — while MCP *authorization* is the
differentiator, and it splits by ecosystem. On the JVM, `mcp-security` is the only client-side
implementation of the MCP auth spec, so the framework choice decides the security story. In
Python, the official SDK ships the auth spec (client credentials, DCR, RFC 8707) as pluggable
`httpx` auth — so the differentiator there is merely whether a framework *exposes* it (LangChain
and OpenAI Agents SDK do; PydanticAI partially; ADK and CrewAI do not). What no ecosystem
replicates is Spring Boot's combination of profile-driven declarative transports plus fail-fast
startup verification — the operational half of this application's design.

## Sources

- LangChain4j: [MCP tutorial](https://docs.langchain4j.dev/tutorials/mcp/) ·
  [langchain4j-spring](https://github.com/langchain4j/langchain4j-spring) ·
  [Quarkus MCP docs](https://docs.quarkiverse.io/quarkus-langchain4j/dev/mcp.html) ·
  [Secure MCP client blog](https://quarkus.io/blog/secure-mcp-client/) ·
  issues [#2898](https://github.com/langchain4j/langchain4j/issues/2898),
  [#2944](https://github.com/langchain4j/langchain4j/issues/2944),
  [#2765](https://github.com/langchain4j/langchain4j/issues/2765)
- Koog: [MCP docs](https://docs.koog.ai/model-context-protocol/) ·
  [Spring Boot starter](https://docs.koog.ai/spring-boot/) ·
  [OpenTelemetry support](https://docs.koog.ai/opentelemetry-support/) ·
  discussions [#717](https://github.com/JetBrains/koog/discussions/717),
  [#940](https://github.com/JetBrains/koog/discussions/940) ·
  [releases](https://github.com/JetBrains/koog/releases)
- Embabel: [embabel-agent](https://github.com/embabel/embabel-agent)
  (`QuiteMcpClientAutoConfiguration`, `McpToolGroup.kt`, `ToolGroupsConfiguration.kt`) ·
  [1.5.1 guide](https://docs.embabel.com/embabel-agent/guide/1.5.1/) ·
  [InfoQ on 1.0 GA](https://www.infoq.com/news/2026/08/embabel-1/)
- MCP Python SDK OAuth clients: [oauth-clients guide](https://py.sdk.modelcontextprotocol.io/client/oauth-clients/)
- LangChain/LangGraph: [MCP guide](https://docs.langchain.com/oss/python/langchain/mcp) ·
  [langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters) ·
  issues [#273](https://github.com/langchain-ai/langchain-mcp-adapters/issues/273),
  [#239](https://github.com/langchain-ai/langchain-mcp-adapters/issues/239)
- OpenAI Agents SDK: [MCP docs](https://openai.github.io/openai-agents-python/mcp/) ·
  [`src/agents/mcp/server.py`](https://github.com/openai/openai-agents-python/blob/main/src/agents/mcp/server.py)
- PydanticAI: [MCP client docs](https://pydantic.dev/docs/ai/mcp/client/) ·
  [FastMCP OAuth](https://gofastmcp.com/clients/auth/oauth) ·
  [Logfire/OTel](https://pydantic.dev/docs/ai/logfire/) ·
  [OTel GenAI MCP semconv](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/mcp.md)
- Google ADK: [MCP tools docs](https://adk.dev/tools-custom/mcp-tools/) ·
  [Agent Config YAML](https://google.github.io/adk-docs/agents/config/) ·
  [changelog](https://github.com/google/adk-python/blob/main/CHANGELOG.md) ·
  issues [#3449](https://github.com/google/adk-python/issues/3449),
  [#2168](https://github.com/google/adk-python/issues/2168),
  [#3331](https://github.com/google/adk-python/issues/3331)
- CrewAI: [MCP overview](https://docs.crewai.com/en/mcp/overview) ·
  [streamable HTTP](https://docs.crewai.com/en/mcp/streamable-http) ·
  [AMP connected MCP (commercial OAuth)](https://docs-platform.crewai.com/platform/en/guides/custom-mcp-server) ·
  issue [#2928](https://github.com/crewAIInc/crewAI/issues/2928)
