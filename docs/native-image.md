# GraalVM native image: one binary per mode

[← back to the README](../README.md)

A native image freezes bean-shaping profile conditions at build time — Spring AOT's closed-world
assumption — so one binary serves one composition; runtime profiles cannot turn a web image into
the REPL or swap the transport. Two axes shape beans (web vs `cli`, stdio vs `mcp-http`), giving
four images for the six run modes: `mcp-stdio` vs `mcp-stdio-docker` differ only in property
values, and that switch alone stays available at run time.

## Baking a composition

Bake the composition with `-PaotProfiles` (it feeds `processAot`; no credentials or servers are
needed at build time). The property is a tracked task input, so changing it re-runs AOT
processing on its own. Each build overwrites `build/native/nativeCompile/solr-mcp-client`, so
move the binary aside before building the next.

| Bake with | Launch as |
| --- | --- |
| *(nothing — default: web, stdio)* | *(no profiles — `mcp-stdio` default)*, or `--spring.profiles.active=mcp-stdio-docker` |
| `-PaotProfiles=cli,mcp-stdio` | `--spring.profiles.active=cli,mcp-stdio` or `cli,mcp-stdio-docker` |
| `-PaotProfiles=mcp-http` | `--spring.profiles.active=mcp-http` |
| `-PaotProfiles=cli,mcp-http` | `--spring.profiles.active=cli,mcp-http` |

```bash
./gradlew nativeCompile -PaotProfiles=cli,mcp-stdio
mv build/native/nativeCompile/solr-mcp-client solr-mcp-client-cli-stdio

export OPENAI_API_KEY=sk-...
export SOLR_MCP_JAR=/absolute/path/to/solr-mcp.jar
./solr-mcp-client-cli-stdio --spring.profiles.active=cli,mcp-stdio
```

The build needs a GraalVM JDK: the plugin finds `native-image` via `GRAALVM_HOME`, then
`JAVA_HOME`.

## Launching

At launch, activate the same profiles the image was baked with (the `-docker` variant being the
one allowed swap); the usual environment applies unchanged. No JVM flags are needed: native
access is granted at image build time, and the JEP 498 unsafe warning is a JVM-only concern.

If startup fails — most commonly because no Solr MCP server is reachable — the process prints
`Application run failed` but may not exit on its own: the MCP SDK's executor threads outlive the
cancelled startup and keep the process alive, so kill it rather than waiting.

## One artifact for every mode instead

To keep a single artifact that serves *every* composition, run the jar on a JVM — optionally
with the JDK 25 AOT cache (JEP 483/514) for fast startup without freezing the bean graph.
