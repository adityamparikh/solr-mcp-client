# Model provider configuration

[← back to the README](../README.md)

## How the provider is chosen

**Purely by what is on the classpath.** There is no provider-selection code in this application —
`SolrAssistant` depends on `ChatClient`, and `ChatClientConfiguration` builds it from the
auto-configured `ChatClient.Builder`. No provider-specific `ChatModel` type is imported anywhere in
`src/main/java`.

Spring AI's per-provider auto-configurations are each guarded with
`@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "<provider>", matchIfMissing = true)`,
so a starter that is present auto-configures itself unless told otherwise. **Keep exactly one
`spring-ai-starter-model-*` starter on the classpath** — two would auto-configure two `ChatModel`
beans and make `ChatClient.Builder` ambiguous. (`spring.ai.model.chat` can pin one explicitly, or
`none` can disable chat entirely, as the web slice tests do.)

`build.gradle.kts` currently has OpenAI active and Anthropic commented out:

```kotlin
dependencies {
    // ...
    implementation(libs.spring.ai.openai)
    //  implementation(libs.spring.ai.anthropic)
}
```

> **Spring AI 2.0 flattened the `.options.` segment.** The canonical model key is
> `spring.ai.<provider>.chat.model`; the older `spring.ai.<provider>.chat.options.model` still binds
> but is deprecated. Prefer the flat form, and be wary of copying snippets written for 1.x.

## A missing API key fails on the first request, not at startup

Nothing checks that you supplied one. The starters apply whatever the placeholder binds — the
empty default included — and the context starts. A missing or wrong key therefore surfaces as a
provider authentication failure on the **first chat request**: a `502` with the generic
`"The Solr assistant could not complete the request."` detail and the real cause in the server
log. If chat fails immediately on an otherwise healthy deployment, check the key first.

For Anthropic specifically, `AnthropicChatAutoConfiguration` guards the key with a **null check,
not a blank check** — `if (connectionProperties.getApiKey() != null) builder.apiKey(...)`. That
null guard has a consequence worth knowing. The Anthropic client falls back to reading
`ANTHROPIC_API_KEY`, then `ANTHROPIC_AUTH_TOKEN`, from the environment itself — but **only when no
key was configured at all**. Since `api-key: ${ANTHROPIC_API_KEY:}` binds an empty string when the
variable is unset, that empty string counts as "configured" and suppresses the fallback. In
practice:

| | Result |
| --- | --- |
| `ANTHROPIC_API_KEY` set | Works — the placeholder carries it into `spring.ai.anthropic.api-key` |
| `ANTHROPIC_AUTH_TOKEN` set, `ANTHROPIC_API_KEY` unset | **Does not work** — the empty binding wins before the SDK's fallback is reached |
| Neither set | Starts; fails on the first chat request |

To use an auth token instead of an API key, bind it explicitly —
`SPRING_AI_ANTHROPIC_API_KEY=$ANTHROPIC_AUTH_TOKEN`.

The empty defaults are deliberate: an unresolvable `${...}` placeholder breaks *every* test that
starts a context whenever the variable is absent, including contexts that never build a `ChatModel`.
That is a worse failure mode than a late auth error. They also keep the block for whichever starter
is *not* on the classpath inert, so a missing key for the inactive provider cannot fail anything.

All of this is the opposite of how the MCP side behaves: `McpToolVerifier` refuses to start without
a working connection, and `SOLR_MCP_OAUTH_CLIENT_ID`/`_SECRET` have no defaults at all.

## OpenAI (the current default)

```bash
export OPENAI_API_KEY=sk-...
export OPENAI_MODEL=gpt-5-mini             # optional; this is the default in application.yml
```

| | Property | Env var used here | Default |
| --- | --- | --- | --- |
| API key | `spring.ai.openai.api-key` *(or `spring.ai.openai.chat.api-key`)* | `OPENAI_API_KEY` | — |
| Model | `spring.ai.openai.chat.model` | `OPENAI_MODEL` | `gpt-5-mini` |
| Base URL | `spring.ai.openai.base-url` *(or `spring.ai.openai.chat.base-url`)* | — | `https://api.openai.com` |

Unlike Anthropic, the OpenAI starter accepts the key and base URL at *either* level:
`OpenAiCommonProperties` owns the connection-level pair and `OpenAiChatProperties` offers a
per-chat override, with the chat-level value winning when set. Prefer the connection-level form.

## Switching to Anthropic

Flip the two lines in `build.gradle.kts`:

```kotlin
dependencies {
    // ...
    //  implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.anthropic)
}
```

Then:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export ANTHROPIC_MODEL=claude-sonnet-5    # optional; this is the default in application.yml
```

| | Property | Env var used here | Default |
| --- | --- | --- | --- |
| API key | `spring.ai.anthropic.api-key` | `ANTHROPIC_API_KEY` | — |
| Model | `spring.ai.anthropic.chat.model` | `ANTHROPIC_MODEL` | `claude-sonnet-5` |
| Base URL | `spring.ai.anthropic.base-url` | — | Anthropic's API |

Other chat options follow the same flat shape: `spring.ai.anthropic.chat.temperature`,
`.max-tokens`, `.top-p`, `.top-k`, `.thinking`, `.tool-choice`, `.stop-sequences`.

> **The API key is a connection-level property, the model is a chat-level one.** This asymmetry is
> easy to get wrong and fails silently. `AnthropicConnectionProperties` uses the prefix
> `spring.ai.anthropic` and owns `api-key` and `base-url`; `AnthropicChatProperties` uses
> `spring.ai.anthropic.chat` and has **no `api-key` field at all**. So
> `spring.ai.anthropic.chat.api-key` binds nothing — if you find it in older configuration, or nest
> the key under `chat:` by symmetry with the OpenAI block, the key is silently discarded. OpenAI is
> the exception, not the rule: it accepts a key at both levels.

## Any other provider

The general recipe — no application code changes:

1. Add the coordinate to `gradle/libs.versions.toml` (the Spring AI BOM supplies the version):

   ```toml
   [libraries]
   spring-ai-ollama = { module = "org.springframework.ai:spring-ai-starter-model-ollama" }
   ```

2. Swap it into `build.gradle.kts` in place of the current starter.
3. Set that provider's connection properties.

Do not guess property names — each provider page in the Spring AI reference documents its own:

- [Chat Models overview](https://docs.spring.io/spring-ai/reference/api/chatmodel.html)
- [Chat Model comparison / provider index](https://docs.spring.io/spring-ai/reference/api/chat/comparison.html)

## OpenAI-compatible endpoints

Many providers and gateways speak the OpenAI Chat Completions API, and Spring AI's OpenAI starter is
the documented way to consume them: keep `spring-ai-starter-model-openai` and override the base URL.
Commonly used this way are **Groq**, **OpenRouter**, **Mistral-compatible gateways**,
**Azure-hosted OpenAI proxies**, and self-hosted routers such as **LiteLLM**, **vLLM** and
**LocalAI**.

```yaml
spring:
  ai:
    openai:
      base-url: https://api.groq.com/openai      # provider's OpenAI-compatible root
      api-key: ${GROQ_API_KEY}
      chat:
        model: llama-3.3-70b-versatile
```

or entirely from the environment:

```bash
export SPRING_AI_OPENAI_BASE_URL=https://api.groq.com/openai
export SPRING_AI_OPENAI_API_KEY=gsk_...
export SPRING_AI_OPENAI_CHAT_MODEL=llama-3.3-70b-versatile
```

Check the target provider's docs for the exact root path it expects (some want the host, some the
`/v1` path), and Spring AI's own
[OpenAI-compatible endpoint notes](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
before assuming the shape.

## Local models

**Ollama** has a first-class starter:

```toml
spring-ai-ollama = { module = "org.springframework.ai:spring-ai-starter-model-ollama" }
```

```bash
ollama pull qwen3:8b        # any model that supports tool calling
```

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: qwen3:8b
```

See the [Ollama chat docs](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) for
model pull-on-start behaviour and the full option list.

> **Raise the context window, or tool calling fails in a way that looks like the model not
> supporting it.** Ollama's own `num_ctx` default is small and version-dependent, and Spring AI sets
> no default of its own — so the default applies. This application attaches the whole Solr MCP
> toolset, and a schema response is large; overflow that window and the tool definitions are the
> first thing dropped. The model then answers from its own knowledge, with no error — the *exact*
> symptom described in [The tool-calling constraint](#the-tool-calling-constraint) below, which is
> why the two are easy to confuse. If a tool-calling model still never calls Solr, raise this before
> concluding the model is at fault:
>
> ```yaml
> spring:
>   ai:
>     ollama:
>       chat:
>         num-ctx: 32768        # flat form; .options.num-ctx is deprecated in favour of it
> ```
>
> Bigger windows cost memory, so size it to the model and machine rather than maximising it.

**Anything else that serves the OpenAI API locally** — llama.cpp's `llama-server`, vLLM, LM Studio,
LocalAI, Ollama's own `/v1` shim — works through the OpenAI starter with a dummy key, since most
local servers do not check it:

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:8000       # llama-server / vLLM / LM Studio / LocalAI
      api-key: not-needed
      chat:
        model: <whatever the server calls it>
```

## The tool-calling constraint

**This application is useless without tool calling.** The whole point is that the model invokes the
Solr MCP tools attached in `ChatClientConfiguration`. A model or endpoint that does not implement
tool/function calling will start the application perfectly cleanly and then simply never call Solr —
answering from its own knowledge, or apologising, with no error anywhere. When substituting a
provider or a local model, confirm tool-calling support first.

[Logging the LLM exchange](logging.md) is how you tell the difference between "the model called
Solr" and "the model answered from memory".
