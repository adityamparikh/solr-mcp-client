# Architecture

[← back to the README](../README.md)

Packaged by capability, with configuration living next to what it configures — no `config`
grab-bag, no layer packages.

| Package | Role |
| --- | --- |
| `assistant` | `SolrAssistant` (the transport-independent seam) and its `ChatClient` wiring |
| `mcp` | Everything about *reaching* the Solr MCP server: the outbound OAuth2 service token and the startup tool check |
| `observability` | Trace propagation into MCP requests, which Spring Boot's instrumentation cannot reach — see [Observability](observability.md) |
| `web` | The REST service this application serves by default: controller, RFC 9457 error mapping, inbound security posture, CORS, OpenAPI |
| `cli` | The interactive shell the `cli` profile serves instead: Spring Shell commands over `SolrAssistant`, plus the no-op runner that keeps every other profile REPL-free |

`SolrAssistant` is public; the chat client bean and its configuration are package-private. The test
for placement: if replacing the REST facade with an in-process UI would leave a class still needed,
it does not belong in `web`.

Profiles come in two composable axes: the `mcp-*` profiles select the *outbound* transport, and the
single `cli` profile selects the *inbound* adapter (a REPL instead of the web server,
`spring.main.web-application-type=none`). `cli` must always be composed with one `mcp-*` profile —
activating it alone replaces the `mcp-stdio` default rather than merging with it.

## A pluggable UI layer

`SolrAssistant` is the seam a UI binds to. An in-process UI injects that bean directly rather than
calling this application's own HTTP endpoints — the `cli` package is exactly that, and a browser UI
would bind to the same seam.
[spring-ai-vaadin](https://springaicommunity.mintlify.app/projects/incubating/spring-ai-vaadin) is
the intended reference — note it is currently an **example application** rather than a released
library (`0.0.1-SNAPSHOT`, not on Maven Central, still on Spring Boot 3.4.5), so adopting it means
adding `com.vaadin:vaadin-spring-boot-starter` and porting its chat view onto `SolrAssistant`, not
depending on it. A React frontend lives on that project's `hilla` branch.

## Streaming

`SolrAssistant.stream()` is the streaming counterpart of `send()` — same conversation scoping, same
MCP tools, delivered as the model produces it. Both adapters expose it: the REST facade as
`POST /api/v1/stream` (streamed plain text) and the shell as its `solr-mcp stream` command. A UI
binds to the seam method, not to either adapter.

Two things about it are worth knowing before building on it.

**Only the final answer streams.** Spring AI resolves the tool loop underneath the publisher and
filters the intermediate tool-call responses out before a subscriber sees them —
`ToolCallingAdvisor.adviseStream` ends with `.filter(ccr -> !isToolCallResponse(ccr.chatResponse()))`.
So the wait for the tool round-trips is silent, and streaming shortens the wait for a *long* answer
rather than a *slow* one. Surfacing which tools ran, and with what arguments, means observing the
chain at a second point — a `StreamAdvisor` at order `0`, beside `SimpleLoggerAdvisor`, which sits
*inside* the tool loop and so sees each round's response before that filter applies. That would be
the foundation for a UI that shows the query behind an answer; it is deliberately not built yet.

**Servlet, not WebFlux, is not an obstacle.** Streaming a response body is a Servlet async feature;
WebFlux is about concurrency scaling, which `spring.threads.virtual.enabled` already addresses here.
Reactor is on the classpath transitively, so Spring MVC adapts a returned `Flux` onto Servlet async
without any change of server.

The remaining gap for a chat UI is a widened reply type carrying tool calls alongside tokens. Adding
`Flux<AssistantEvent> streamEvents(...)` later is additive and breaks neither `send()` nor
`stream()`.
