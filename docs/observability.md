# Observability

[← back to the README](../README.md)

## What is wired in

| Signal | How it leaves the application |
| --- | --- |
| Traces | `spring-boot-starter-opentelemetry` exports over OTLP through the OpenTelemetry SDK, configured by the standard `OTEL_*` environment variables |
| Logs | Same starter, same SDK, same variables |
| Metrics | Micrometer meters, bridged into that same SDK and exported over **OTLP/gRPC** — but only when the environment announces a gRPC collector (see below) |

Sampling is set to `management.tracing.sampling.probability: 1.0` — **every** request is traced.
That is the right setting for development and for the low-traffic deployments this application
targets; dial it down before pointing real load at a paid tracing backend.

Only `health` and `info` are exposed through the actuator
(`management.endpoints.web.exposure.include`); there is no metrics scrape endpoint. Metrics leave
by push, or not at all.

## Metrics over OTLP/gRPC

Spring Boot's OpenTelemetry starter has an asymmetry: traces and logs go through the OpenTelemetry
SDK, which speaks both OTLP transports, but metrics stay on Micrometer's own OTLP registry
(`micrometer-registry-otlp`), which speaks **only OTLP over HTTP**. A collector that accepts only
gRPC — IntelliJ IDEA's built-in OpenTelemetry receiver is one — would receive traces and logs but
reject every metrics POST.

The `observability` package closes that gap. `OtlpGrpcMetricsConfiguration` activates when the
environment announces a gRPC collector and routes metrics through the SDK's gRPC exporter instead;
`OpenTelemetryMeterRegistry` bridges every Micrometer meter — JVM, HTTP server, Spring AI — into
it. Without the announcement the configuration is inert.

Everything flows from the standard OpenTelemetry variables — nothing names the collector in this
repository's configuration:

| Variable | Effect | Default |
| --- | --- | --- |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` switches the gRPC metrics path on; anything else leaves it inert | — |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Where all signals go | `http://localhost:4317` |
| `OTEL_METRIC_EXPORT_INTERVAL` | Metrics push interval, milliseconds | `60000` (the OTel specification's default) |

IntelliJ injects all of these when a run configuration has monitoring enabled — including a fresh
random port per IDE session — so running under the IDE needs no configuration at all, and the
IDE's *Monitoring* tool window shows traces and metrics together.

Micrometer's HTTP registry is **excluded from the classpath** in `build.gradle.kts` rather than
disabled by property: left in place it pushes to its `localhost:4318` default and warns on every
interval, and no environment this application runs in has an OTLP/HTTP collector. Re-add the
dependency explicitly if one ever becomes a real target.

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
