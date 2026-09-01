# Observability

[← back to the README](../README.md)

## What is wired in

| Signal | How it leaves the application |
| --- | --- |
| Traces | `spring-boot-starter-opentelemetry` exports over OTLP through the OpenTelemetry SDK, configured by the standard `OTEL_*` environment variables |
| Logs | Same starter, same SDK, same variables |
| Metrics | Micrometer's OTLP registry, pushing over **OTLP/HTTP** to `http://localhost:4318/v1/metrics` unless `management.otlp.metrics.export.url` says otherwise |

Sampling is set to `management.tracing.sampling.probability: 1.0` — **every** request is traced.
That is the right setting for development and for the low-traffic deployments this application
targets; dial it down before pointing real load at a paid tracing backend.

Only `health` and `info` are exposed through the actuator
(`management.endpoints.web.exposure.include`); there is no metrics scrape endpoint. Metrics leave
by push, or not at all.

## The gRPC caveat for metrics

Spring Boot's OpenTelemetry starter has an asymmetry: traces and logs go through the OpenTelemetry
SDK, which speaks both OTLP transports, but metrics stay on Micrometer's own OTLP registry
(`micrometer-registry-otlp`), which speaks **only OTLP over HTTP** — gRPC support is a
[still-open Micrometer feature request](https://github.com/micrometer-metrics/micrometer/issues/5040).
This application accepts that asymmetry rather than working around it:

- Against a full collector that opens both ports — the OpenTelemetry Collector, the Grafana LGTM
  stack — everything works: point `management.otlp.metrics.export.url` at the collector's
  `:4318/v1/metrics` and the `OTEL_*` variables at `:4317` or `:4318` as it prefers.
- Against a **gRPC-only** collector — IntelliJ IDEA's built-in receiver is one — traces and logs
  arrive but metrics do not, and the registry warns on each push interval that `localhost:4318`
  is not answering. Silence it with `management.otlp.metrics.export.enabled: false` if the noise
  bothers you.

Pointing `management.otlp.metrics.export.url` at a gRPC port does not help: it only moves the HTTP
POST, and a gRPC listener rejects it.

## Trace propagation across MCP calls

The MCP SDK's Streamable HTTP transport builds requests with its own raw JDK `HttpClient` — one of
the few clients Spring Boot's tracing does not instrument — so an outbound tool call would
silently start a fresh, disconnected trace on the Solr MCP server.

`TracePropagatingHttpRequestCustomizer` fixes that in the `mcp-http` profile: it writes the
caller's trace context (W3C `traceparent` by default — whatever `Propagator` Spring Boot is
configured with) into each outbound MCP request, so the server's spans join the caller's trace.
One chat request then reads as a single trace: inbound HTTP → chat model → MCP tool call → Solr.

Two wiring details live in `McpHttpOAuth2Configuration` and are easy to break by accident:

- **The transport holds a single request customizer.** Registering trace propagation as a second
  customizer would silently *replace* the OAuth2 bearer-token one (or the reverse), so the two are
  composed into one lambda.
- **The trace context is captured at call time**, on the caller's thread, into the SDK's
  `McpTransportContext` — the customizer itself runs on a transport worker thread where the span
  thread-local is empty. The transport-context provider that does the capturing also wraps (not
  replaces) the one `mcp-client-security` installs for authentication.

When no span is current — startup verification, anything outside an observation — requests are
left untouched and the server starts its own trace, exactly as before the class existed.

The stdio profiles get no propagation: there is no HTTP request to carry headers, and the child
process is not instrumented.

## Correlating an error response with the logs

Upstream failures return a generic RFC 9457 detail; the real cause is only in the server log. The
`timestamp` property on every problem response is the correlation key — see
[the REST API guide](rest-api.md) for the error contract.
