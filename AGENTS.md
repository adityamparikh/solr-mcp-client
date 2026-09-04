# AGENTS.md

## Project

`solr-mcp-client` is a Java 25 Spring Boot 4.1.1 REST façade over Apache Solr MCP, using Spring AI
2.0.1 and MCP Java SDK 2.0.0. The base package is `org.apache.solr.mcp.client`.

## Commands

```bash
./gradlew build       # compile + test + 80% instruction coverage gate
./gradlew test
./gradlew bootRun
./gradlew bootRun --args='--spring.profiles.active=mcp-http'
./gradlew bootRun --console=plain --args='--spring.profiles.active=cli,mcp-stdio'   # interactive shell
./gradlew sonar
```

Gradle is the only build system; the Maven build was removed. Versions live in
`gradle/libs.versions.toml` — add dependencies there, not inline.

## Architecture

Packaged by capability, with configuration living next to what it configures — there is no `config`
grab-bag and no layer packages.

- `assistant`: `SolrAssistant` (the transport-independent seam) and the `ChatClient` wiring it
  needs. Both the chat client bean and its configuration are package-private; only `SolrAssistant`
  is public. New assistant behaviour belongs here, not in the controller. A UI layer (Vaadin/Hilla,
  CLI) injects `SolrAssistant` directly instead of calling the application's own HTTP endpoints.
- `mcp`: everything about reaching the Solr MCP server — the outbound OAuth2 service token for the
  `mcp-http` profile and the startup check that a connection is configured at all.
- `web`: the REST service this application serves by default — controller, RFC 9457 error mapping,
  inbound security posture, OpenAPI. Transport only; no business logic.
- `cli`: the interactive shell the `cli` profile serves instead — Spring Shell commands over
  `SolrAssistant` (injected directly, reusing `ChatRequest`/`ChatReply`), plus the no-op runner
  that keeps every other profile REPL-free. Transport only; no business logic.

There is no `model` package. Which chat model provider is active is not decided in code at all: it
follows from the single `spring-ai-starter-model-*` dependency on the classpath, which Spring AI
auto-configures into the one `ChatModel` it finds.

Both `mcp` and `web` involve HTTP, in opposite directions. The test for placement: if replacing the
REST facade with an in-process UI would leave a class still needed, it does not belong in `web`.
Each package carries a `package-info.java` stating its role — keep it current.

When adding a capability, give it a package and put its `@Configuration` inside it. Do not create a
shared `config` or `service` package.

## Spring wiring rules

- **Never use `@ConditionalOnBean` in application configuration.** It is evaluated before
  auto-configurations register their beans, so it silently never matches. Three defects in this
  codebase came from exactly that; `McpHttpTransportWiringTest` guards against a regression.
- **Keep application code model-agnostic.** Depend on `ChatClient` / `ChatClient.Builder`, never on
  a provider type such as `OpenAiChatModel` or `AnthropicChatModel`, and never inject a provider by
  `@Qualifier`. Provider names belong only in the `spring-ai-starter-model-*` dependency in
  `build.gradle.kts` and in per-provider defaults in `application.yml`; switching providers must not
  touch anything else. If more than one model starter is ever present, `spring.ai.model.chat` has to
  name the winner or startup fails rather than guessing.
- Never set `spring.ai.chat.client.enabled=false`. It removes the auto-configured builder along with
  Spring AI's `ChatClientBuilderConfigurer` — observation wiring and the tool-calling advisor.
- Build the chat client from the auto-configured `ChatClient.Builder`; building from a `ChatModel`
  bypasses Spring AI's own builder configurer. Configure that builder directly — it is
  `@Scope("prototype")`, so each injection point gets its own. Do not use a
  `ChatClientBuilderCustomizer` for assistant-specific settings: customizers decorate *every*
  builder in the application, so one assistant's prompt and tools would leak into unrelated ones.
- Hand the `ToolCallbackProvider` to `defaultTools(...)` as-is. Calling `getToolCallbacks()` eagerly
  snapshots the MCP tool list while the context is still starting.
- **Never declare a `ToolCallingAdvisor`.** Spring AI 2.0 moved tool calling into the advisor chain,
  and `DefaultChatClientRequestSpec.buildAdvisorChain()` registers one per request unless the chain
  already holds a `ToolAdvisor`. Declaring one only suppresses the framework's and pins defaults
  that already match what this application wants (order `MIN_VALUE + 300`, internal conversation
  history on). Registration happens on `call()`/`stream()`, not on `prompt()` — a chain read off
  `prompt()` does not contain it yet, which is why `ChatClientConfigurationTest` drives a real call
  against a stub model before asserting. Recipes written against the `2.0.0-M` milestones still
  spell it `ToolCallAdvisor` and set `advisorOrder(HIGHEST_PRECEDENCE + 300)` plus
  `disableInternalConversationHistory()` by hand; on 2.0.1 the first is a shim subclass and the
  other two are the defaults.
- Advisor order in `ChatClientConfiguration` is load-bearing in two directions, and both break
  silently. Chat memory must stay **outside** the tool loop (`MIN_VALUE + 200` < `MIN_VALUE + 300`):
  `autoRegisterToolCallingAdvisor()` reads exactly that comparison and turns the tool advisor's
  internal conversation history *off* when a `MemoryAdvisor` is ordered after it, costing the loop
  the messages it accumulates across iterations. `SimpleLoggerAdvisor` must stay **inside** it
  (order `0`): that is what makes tool-negotiation traffic visible instead of just the opening
  request and final answer. `ChatClientConfigurationTest` guards both.
- `SimpleLoggerAdvisor` is registered unconditionally and gated by its log level alone — it
  self-guards on `isDebugEnabled()`. The switch is
  `logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=DEBUG` (DEBUG, not
  the TRACE of pre-GA recipes), commented out in `application.yml`. It is a debugging tool, not a
  deployment setting: it logs whole prompts and whole tool results, so user queries and indexed Solr
  documents reach the log. Do not enable it with a broad `org.springframework.ai=DEBUG`.
- For the outbound service token use `AuthorizedClientServiceOAuth2AuthorizedClientManager`. The
  manager Spring Security registers by default is request-bound and throws
  "servletRequest cannot be null" off a request thread, which includes MCP client initialization.
- Configuration classes that need `HttpSecurity` must be `@ConditionalOnWebApplication(SERVLET)`.
- **`ChatClient.stream()` never surfaces tool calls.** `ToolCallingAdvisor.adviseStream` aggregates
  each round, drives the tool recursion, then ends with
  `.filter(ccr -> !isToolCallResponse(ccr.chatResponse()))` — so a subscriber sees the final answer's
  deltas and nothing of the negotiation. Anything that needs tool names or arguments must observe the
  chain at a second point: a `StreamAdvisor` at order `0` (beside `SimpleLoggerAdvisor`, *inside* the
  tool loop) sees each round's response before that filter. Do not try to sort tool calls out of the
  stream; they are not in it.
- **`ProblemDetailExceptionHandler` does not cover a committed stream.** On
  `POST /api/v1/stream` the advice still maps everything raised before the first frame, but once
  a frame flushes the status is `200` and a failure can only be reported in-band, as the terminal
  `error` event the controller emits. Keep the wording identical to the advice's by referencing
  `ProblemDetailExceptionHandler.FAILED_DETAIL` rather than repeating the string, and never put the
  cause in the frame — the advice does not return it either.
- API versions are declared with `@RequestMapping(version = ...)` (Spring Framework 7 built-in
  versioning), never as a literal path prefix. Adding a version means adding handlers, not paths.
- `spring.mvc.apiversion.default` is set to `v1`, matching `SolrAssistantController.V1`; keep the
  two in step. Setting it forces `required: false`, since Spring refuses to start with
  `required: true` alongside a default. Note it does not make unversioned URLs work:
  `PathApiVersionResolver` returns whatever segment sits at the index rather than null, so the
  default is never reached by dropping the segment, and `/api/chat` is a 404 regardless because the
  segment is still part of the controller's path template. Serving an unversioned URL would require
  a `WebMvcConfigurer` with
  `usePathSegment(int, Predicate<RequestPath>)`; do not add one without a reason to support
  unversioned callers.

## Configuration and security

- Profiles come in exactly two axes. The `mcp-*` profiles name the **outbound MCP transport**, and
  the single `cli` profile names the **inbound adapter** — a Spring Shell REPL in place of the web
  server (`spring.main.web-application-type=none` in `application-cli.yml`). The axes compose
  (`cli,mcp-http` is legal); do not add further profiles that change the web layer, and keep the
  `mcp-` prefix for transports. Activating `cli` alone drops the `mcp-stdio` default —
  `spring.profiles.default` is replaced by an explicit activation, never merged — so the CLI is
  always run as `cli,<mcp-profile>`.
- The shell's commands are a `@CommandGroup(name = "Solr MCP Commands", prefix = "solr-mcp")`, so
  `@Command(name = "chat")` registers as **`solr-mcp chat`** — `CommandFactoryBean` builds the name
  as `prefix + " " + name`, and `CommandRegistry` matches that whole string. Keep new commands in
  that group rather than adding top-level words, and name them in user-facing text (hints, README)
  with the prefix. `@CommandGroup` is meta-annotated `@Component`, so it also makes the class a
  bean — do not add a second stereotype.
- Spring Shell has **no property that disables the shell** (`spring.shell.interactive.enabled=false`
  selects non-interactive mode instead). `ShellSuppressionConfiguration` overrides the
  auto-configured `springShellApplicationRunner` with a no-op under `@Profile("!cli")`; that back-off
  is by `@ConditionalOnMissingBean`, so adding any other `ApplicationRunner` bean would silently
  disable the `cli` shell — use an `ApplicationListener` for startup work instead.
- `mcp-stdio` is the default and launches the local Solr MCP process. `SOLR_MCP_JAR` must be an
  absolute path. Pass the child only what it cannot default or inherit: `SOLR_URL`, which the SDK's
  environment allowlist would otherwise drop. Do not name the server's profile — it defaults to
  `stdio` — and note that `PROFILES` is the server's own placeholder, not something Spring reads.
- `mcp-stdio-docker` is the same transport with a container as the child. It exists as its own
  profile because `env:` cannot reach a container — the SDK applies that map to the `docker` CLI —
  so container settings are `-e` flags in `args`, and because `SOLR_URL` must name
  `host.docker.internal` rather than `localhost`. Keep the two profiles in step when either changes.
- `mcp-http` connects over Streamable HTTP with OAuth2 client credentials, applied by
  mcp-client-security. It must use a dedicated service token — never forward a REST caller's token,
  which is also why the library's authorization-code default is replaced with its client-credentials
  customizer rather than left in place.
- Bind each provider's `api-key` at the level that provider's starter actually reads, and check the
  starter's `spring-configuration-metadata.json` before moving one. The levels differ:
  `spring.ai.openai.chat.api-key` is real (a per-chat override of `spring.ai.openai.api-key`), but
  Anthropic binds only `spring.ai.anthropic.api-key` and has no `chat.api-key`. An api-key nested at
  a level the starter does not bind is discarded in silence — no startup error, no warning.
  `application.yml`'s Anthropic key had exactly that bug.
- Exactly one model provider key may be set unless `spring.ai.model.chat` names the provider;
  otherwise startup fails rather than guessing. Web slice tests pin `spring.ai.model.chat` so they
  do not depend on which keys a developer exported.
- `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `SOLR_MCP_OAUTH_CLIENT_SECRET` and other secrets belong in environment
  variables, never in committed configuration. Properties that must be set have no default so that
  misconfiguration fails at startup rather than on the first request.
- `McpToolVerifier` fails startup unless the Solr MCP server offers tools. Listing them answers
  "is a connection configured" and "is this the right server" in one step, so do not add a separate
  connection check. Verification is unconditional — no property turns it off, because an assistant
  with nothing to call is never a state worth starting in. Listing the tools is what first drives
  Spring AI's `LifecycleInitializer`, so the check connects even when
  `spring.ai.mcp.client.initialized` is false: that property defers connecting, and verifying
  necessarily undoes the deferral. A context that must not reach a server replaces the bean.
- Never bind a comma-separated property straight to a `List`/`Set` with `@Value`. That conversion
  needs Boot's `ApplicationConversionService`, so it silently yields one element in contexts that
  lack it (a bare `ApplicationContextRunner`, for one). Bind a `String` and split it with
  `StringUtils.commaDelimitedListToSet`.
- The REST façade performs **no inbound authentication** by design. Do not add one without asking;
  do not advertise a security scheme in OpenAPI that is not enforced.
- Conversation id travels in the `X-AI-Conversation-Id` header in both directions and is not
  duplicated in the response body. Never introduce a shared default id — without inbound auth it
  merges unrelated callers' chat memory. `ApiCorsConfiguration` puts the header in
  `Access-Control-Expose-Headers`; a browser cannot read it cross-origin otherwise and continuity
  breaks with no error. If the header is ever renamed, rename it there too.
- CORS origins come from `solr.mcp.client.cors.allowed-origins` and default to none. Never register
  a CORS mapping without explicit origins — the unqualified form allows every origin, and this
  facade has no inbound authentication.
- Upstream failures return a generic RFC 9457 detail and are logged server-side; provider messages
  carry endpoint URLs and payloads that must not reach callers.

## Testing

- `./gradlew build` enforces 80% instruction coverage (JaCoCo), excluding the application class.
- Use `@WebMvcTest` with `@MockitoBean` for REST contracts, plain unit tests for `SolrAssistant` and
  the config classes, and `@SpringBootTest` for wiring that only fails in a real context.
- Context tests set `spring.ai.mcp.client.initialized=false` so the transport binds without
  launching a child process or opening a network connection, and replace `McpToolVerifier` with
  `@MockitoBean`. The property alone is not enough: verification lists the server's tools, which
  drives Spring AI's `LifecycleInitializer` and opens the connection the property deferred.
- A `@SpringBootTest` executes `ApplicationRunner` beans. Context tests that activate the `cli`
  profile must therefore replace the shell's runner —
  `@MockitoBean(name = "springShellApplicationRunner")` — or the REPL starts reading the test JVM's
  console, and must pin `spring.shell.context.close=false` or `application-cli.yml`'s close-on-exit
  listener shuts the test context down as soon as it is ready.
- Do not require a live Solr, MCP server, OpenAI account or identity provider for ordinary runs.
- When stubbing a `ChatModel` for a tool-calling test, `getOptions()` must return
  `ToolCallingChatOptions`, not `ChatOptions.builder().build()`. `ToolCallingAdvisor.adviseCall`
  opens with an `instanceof ToolCallingChatOptions` check and delegates past the tool loop entirely
  when it fails — no error and no log, so the test passes vacuously having exercised nothing. A bare
  `mock(ChatModel.class)` is worse still: `getOptions()` returns null and the call NPEs before the
  advisor chain is reached. Stub `ToolCallbackProvider.getToolCallbacks()` too; the default null
  array only surfaces once the loop actually runs.
