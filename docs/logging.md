# Logging the LLM exchange

[← back to the README](../README.md)

`ChatClientConfiguration` registers Spring AI's `SimpleLoggerAdvisor` in the assistant's advisor
chain. It is always present but logs nothing until you raise its level — it self-guards on
`isDebugEnabled()`, so the cost until then is one boolean check per advisor pass. There is no
application property; the log level *is* the switch:

```yaml
logging:
  level:
    org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor: DEBUG
```

or, without editing a file:

```bash
./gradlew bootRun --args='--logging.level.org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor=DEBUG'
```

**DEBUG, not TRACE.** The level changed in Spring AI 2.0 GA. Recipes written against the `2.0.0-M`
milestones still specify TRACE, which now silently produces nothing.

## What you get: the whole tool negotiation, not just the ends

Ask something that requires Solr — "how many documents are in the `films` collection?" — and a single
exchange logs **four** entries, not two:

1. the initial request carrying your question and the Solr tool definitions,
2. the model's response *requesting* a tool call, naming the tool and its arguments,
3. the follow-up request back to the model carrying the tool's results,
4. the final answer returned to the caller.

Entries 2 and 3 are the useful ones. They are what tells you whether the model chose a Solr tool at
all, which arguments it built, and what Solr actually returned — the difference between a wrong
answer caused by a bad query and one caused by the model never querying (see
[The tool-calling constraint](model-providers.md#the-tool-calling-constraint)). An exchange needing
no tools logs the usual two.

Seeing all four depends on advisor ordering. Tool calling in Spring AI 2.0 is itself an advisor
(`ToolCallingAdvisor`, registered automatically — `ChatClientConfiguration` deliberately does not
declare one), and the logger sits *inside* its recursion. Before 2.0 the tool round-trips happened
inside `ChatClient` where no advisor could observe them, and `SimpleLoggerAdvisor` could only ever
log the first and last entry. `ChatClientConfigurationTest` pins the ordering, since reversing it
would degrade the output to two entries with nothing failing.

## Do not leave it on

The output is whole prompts and whole tool results, so user questions and the contents of indexed
Solr documents are written to the log verbatim. Treat it as a debugging tool for a local or staging
run, and enable this one logger rather than a broad `org.springframework.ai=DEBUG`, which switches on
the same payload logging as a side effect.
