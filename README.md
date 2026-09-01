# Solr MCP Client

A Spring Boot REST service that puts a stable HTTP contract in front of a chat model wired to
[Apache Solr MCP](https://github.com/apache/solr-mcp). It is an **MCP client**: it connects to a
separate Solr MCP server, attaches that server's tools to a Spring AI `ChatClient`, and exposes the
resulting assistant over a small JSON API — so a user interface does not have to embed Spring AI, an
MCP SDK, or a model provider's credentials.

```mermaid
flowchart LR
    client["HTTP client"] -- "POST /api/v1/chat" --> app

    subgraph app["this application"]
        direction LR
        controller["SolrAssistantController"] --> assistant["SolrAssistant"]
        assistant --> chatclient["ChatClient"] --> llm["LLM"]
    end

    app -- "stdio or HTTP" --> mcp

    subgraph mcp["Solr MCP server"]
        tools["solr_search<br/>solr_index_...<br/>..."]
    end

    mcp --> solr["Solr<br/>/select · /update · ..."]
```

**The `mcp-*` profiles select how this client *reaches* the Solr MCP server; the single `cli`
profile selects what it serves** — an interactive terminal shell instead of the REST API. The two
axes compose (`cli,mcp-http` is one application reached two ways); without `cli` it is the same
REST service on the same port in every profile.

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

## Documentation

| Guide | Covers |
| --- | --- |
| [REST API](docs/rest-api.md) | Endpoints, the conversation header, RFC 9457 errors, API versioning, CORS, OpenAPI/Swagger UI |
| [Transports](docs/transports.md) | The three `mcp-*` profiles, Keycloak/OAuth2 service-token setup, the audience claim, startup verification |
| [Model providers](docs/model-providers.md) | How the provider is chosen, OpenAI/Anthropic keys, OpenAI-compatible endpoints, local models, the tool-calling constraint |
| [Observability](docs/observability.md) | Traces, logs and metrics over OTLP, the gRPC-only-collector caveat, trace propagation into MCP calls |
| [Logging the LLM exchange](docs/logging.md) | Seeing the full tool negotiation with `SimpleLoggerAdvisor` |
| [Security posture](docs/security.md) | Why inbound is unauthenticated, what OAuth2 actually secures, secrets and error leakage |
| [Architecture](docs/architecture.md) | Package layout and the seam a future UI binds to |
| [GraalVM native image](docs/native-image.md) | One binary per run mode: baking profiles with `-PaotProfiles`, the bake-vs-launch matrix, startup caveats |

[AGENTS.md](AGENTS.md) holds the wiring rules and conventions this codebase holds itself to.

---

## Requirements

- **JDK 25** (the Gradle toolchain will resolve one if it is not the default JDK).
- **An API key for the chat model provider on the classpath.** Out of the box that is OpenAI, so
  `OPENAI_API_KEY`. See [Model providers](docs/model-providers.md) to change it.
  Note the application starts *without* a key and fails on the first chat request instead.
- **A reachable Solr MCP server**, in one of the three shapes described in
  [Transports](docs/transports.md). The application refuses to start if it cannot list any tools
  from one.
- Docker, for the `mcp-stdio-docker` profile only.

---

## Quick start

The default profile is `mcp-stdio` (`spring.profiles.default`), which launches a local Solr MCP
server jar as a child process:

```bash
export OPENAI_API_KEY=sk-...
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
```

`./gradlew bootJar` produces the deployable jar in `build/libs/`; running it directly needs two
JVM flags for a clean console — see the comments on `bootJar` and `bootRun` in
`build.gradle.kts`.

`mcp-stdio` is a **default** profile, not an active one: naming any profile explicitly *replaces*
it. There is no "no transport" fallback — see
[Startup verification](docs/transports.md#startup-verification).

`./gradlew nativeCompile` builds a GraalVM native image — one binary per run mode; see
[GraalVM native image](docs/native-image.md).

### Fast startup with the JDK 25 AOT cache

For faster startup without giving up runtime profile selection, build a JVM AOT cache
(JEP 483/514) instead of a [GraalVM native image](docs/native-image.md): a native image freezes
the bean graph for whichever profiles were active during `processAot`, so one binary cannot serve
both the web facade and the `cli` REPL, while the AOT cache only pre-loads and pre-links classes
and leaves every profile composition available at launch.

```bash
scripts/build-aot-cache.sh            # bootJar + extract + training run -> build/aot-cache/app.aot
scripts/build-aot-cache.sh cli,mcp-http   # optional: train with the composition you launch most

# then launch from the extracted directory with any profiles
cd build/aot-cache
java --sun-misc-unsafe-memory-access=allow -XX:AOTCache=app.aot -jar solr-mcp-client-0.0.1-SNAPSHOT.jar --spring.profiles.active=cli,mcp-http
```

The cache must be used with the extracted jar layout it was trained on, and is invalidated by
rebuilding the jar or changing the JDK — rerun the script after either.

---

## Interactive shell (`cli` profile)

The `cli` profile replaces the web server with a Spring Shell REPL over the same assistant — same
MCP tools, same conversation memory, no HTTP. It composes with any of the three transports, and
must always be composed (activating `cli` alone would also replace the `mcp-stdio` default):

```bash
./gradlew bootRun --console=plain --args='--spring.profiles.active=cli,mcp-stdio'
# or: cli,mcp-stdio-docker | cli,mcp-http
```

At the prompt, the question is one quoted argument (unquoted words would be re-joined with
commas by the shell's argument conversion):

```
solr-mcp "how many documents are in the books collection"
solr-mcp "and how many of those are in stock" # follow-ups continue the conversation
new                                           # forget the conversation, start fresh
help                                          # everything else Spring Shell offers
exit
```

One conversation spans the shell session; `new` releases it and starts another. Unlike the REST
service, the shell needs the model key at first use in the terminal that runs it.

The shell runs on Spring Shell's JLine runner (the `-ffm` starter flavor), so it also works in a
non-PTY console — an IDE run configuration's output window included — by falling back to a JLine
*dumb terminal*: prompt and input work, at the cost of line editing, history and colors. A real
terminal (or the IDE's Terminal tab) gives the full experience.

---

## REST API at a glance

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/chat` | Ask the assistant a question |
| `DELETE` | `/api/v1/chat/{conversationId}` | Release a conversation's retained turns |
| `GET` | `/api-docs` | OpenAPI 3 document |
| `GET` | `/swagger-ui.html` | Swagger UI |
| `GET` | `/actuator/health`, `/actuator/info` | The only exposed actuator endpoints |

The conversation is carried in the `X-AI-Conversation-Id` header in both directions; omit it to
start a new conversation. Errors are RFC 9457 `application/problem+json`, and the API version is a
path segment (`v1` by default) resolved by Spring Framework 7's built-in API versioning. The
[REST API guide](docs/rest-api.md) has the full contract, including the versioning gotchas and CORS.

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
opening a connection — replacing `McpToolVerifier` with `@MockitoBean`, since verifying would open
that connection anyway — and web slice tests pin `spring.ai.model.chat` so they do not depend on
which API keys a developer happens to have exported.

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
