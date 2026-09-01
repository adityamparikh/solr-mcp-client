# Architecture

[← back to the README](../README.md)

Packaged by capability, with configuration living next to what it configures — no `config`
grab-bag, no layer packages.

| Package | Role |
| --- | --- |
| `assistant` | `SolrAssistant` (the transport-independent seam) and its `ChatClient` wiring |
| `mcp` | Everything about *reaching* the Solr MCP server: the outbound OAuth2 service token and the startup tool check |
| `observability` | The two OpenTelemetry gaps Spring Boot leaves open here: metrics over OTLP/gRPC, and trace propagation into MCP requests — see [Observability](observability.md) |
| `web` | The REST service this application *is*: controller, RFC 9457 error mapping, inbound security posture, CORS, OpenAPI |

`SolrAssistant` is public; the chat client bean and its configuration are package-private. The test
for placement: if replacing the REST facade with an in-process UI would leave a class still needed,
it does not belong in `web`.

## Next step: a pluggable UI layer

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
