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
- `model`: which chat model provider is active, decided from the API keys in the environment before
  Spring AI's provider auto-configurations are evaluated. Knows nothing of Solr, MCP or the web
  layer.
- `web`: the REST service this application *is* — controller, RFC 9457 error mapping, inbound
  security posture, OpenAPI. Transport only; no business logic.

Both packages involve HTTP, in opposite directions. The test for placement: if replacing the REST
facade with an in-process UI would leave a class still needed, it does not belong in `web`. Each
package carries a `package-info.java` stating its role — keep it current.

When adding a capability, give it a package and put its `@Configuration` inside it. Do not create a
shared `config` or `service` package.

## Spring wiring rules

- **Never use `@ConditionalOnBean` in application configuration.** It is evaluated before
  auto-configurations register their beans, so it silently never matches. Three defects in this
  codebase came from exactly that; `McpHttpTransportWiringTest` guards against a regression.
- **Keep application code model-agnostic.** Depend on `ChatClient` / `ChatClient.Builder`, never on
  a provider type such as `OpenAiChatModel` or `AnthropicChatModel`, and never inject a provider by
  `@Qualifier`. Provider names belong only in `ChatModelProviderSelector`'s API-key map and in
  per-provider model defaults in `application.yml`; adding a provider must not touch anything else.
- Never set `spring.ai.chat.client.enabled=false`. It removes the auto-configured builder along with
  Spring AI's `ChatClientBuilderConfigurer` — observation wiring and the tool-calling advisor.
- Build the chat client from the auto-configured `ChatClient.Builder`; building from a `ChatModel`
  bypasses Spring AI's own builder configurer. Configure that builder directly — it is
  `@Scope("prototype")`, so each injection point gets its own. Do not use a
  `ChatClientBuilderCustomizer` for assistant-specific settings: customizers decorate *every*
  builder in the application, so one assistant's prompt and tools would leak into unrelated ones.
- Hand the `ToolCallbackProvider` to `defaultTools(...)` as-is. Calling `getToolCallbacks()` eagerly
  snapshots the MCP tool list while the context is still starting.
- For the outbound service token use `AuthorizedClientServiceOAuth2AuthorizedClientManager`. The
  manager Spring Security registers by default is request-bound and throws
  "servletRequest cannot be null" off a request thread, which includes MCP client initialization.
- Configuration classes that need `HttpSecurity` must be `@ConditionalOnWebApplication(SERVLET)`.
- API versions are declared with `@RequestMapping(version = ...)` (Spring Framework 7 built-in
  versioning), never as a literal path prefix. Adding a version means adding handlers, not paths.
- The version is required, never defaulted. `spring.mvc.apiversion.default` fires only when the
  resolver returns null, which a path-segment resolver never does, so a default here is config that
  cannot take effect — and Spring refuses to start with `required: true` alongside one. Serving an
  unversioned URL would require a `WebMvcConfigurer` with
  `usePathSegment(int, Predicate<RequestPath>)`; do not add one without a reason to support
  unversioned callers.

## Configuration and security

- Profiles name the **outbound MCP transport**, never how this application serves its own API — it
  is a REST service in every profile. Hence the `mcp-` prefix. Do not add a profile that changes the
  web layer.
- `mcp-stdio` is the default and launches the local Solr MCP process. `SOLR_MCP_JAR` must be an
  absolute path. The child is a Spring Boot app: pass `SPRING_PROFILES_ACTIVE` (the *server's*
  profile, unrelated to ours), not `PROFILES`.
- `mcp-http` connects over Streamable HTTP with OAuth2 client credentials. It must use a dedicated
  service token — never forward a REST caller's token.
- Exactly one model provider key may be set unless `spring.ai.model.chat` names the provider;
  otherwise startup fails rather than guessing. Web slice tests pin `spring.ai.model.chat` so they
  do not depend on which keys a developer exported.
- `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `SOLR_MCP_OAUTH_CLIENT_SECRET` and other secrets belong in environment
  variables, never in committed configuration. Properties that must be set have no default so that
  misconfiguration fails at startup rather than on the first request.
- `McpToolVerifier` fails startup unless the Solr MCP server offers tools. Listing them answers
  "is a connection configured" and "is this the right server" in one step, so do not add a separate
  connection check. It skips when `spring.ai.mcp.client.initialized` is false, since disabled
  clients have nothing to list.
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
  launching a child process or opening a network connection.
- Do not require a live Solr, MCP server, OpenAI account or identity provider for ordinary runs.
