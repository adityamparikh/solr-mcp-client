#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Produces a JDK 25 AOT cache (JEP 483/514, Project Leyden) for the executable jar, the startup
# accelerator that — unlike a GraalVM native image — keeps every profile composition available at
# launch. Spring AOT's closed-world native image freezes the bean graph for whatever profiles were
# active at build time, so one binary cannot serve both the web facade and the cli REPL; the AOT
# cache only pre-loads and pre-links classes, leaving all runtime condition evaluation intact.
#
# The cache must be built and used against the EXTRACTED form of the jar (Boot packages the app in
# an uber-jar whose nested-jar classloading defeats cache lookups), and it is invalidated by any
# rebuild of the jar or change of JDK — rerun this script after either.
#
#   scripts/build-aot-cache.sh [training-profiles]
#
# training-profiles defaults to the application's own default (mcp-stdio, web). The cache still
# helps other compositions — classes it missed simply load the normal way — but training with the
# composition you launch most keeps the win largest, e.g.:
#
#   scripts/build-aot-cache.sh cli,mcp-http
#
# The training run boots the real application context and exits at refresh (spring.context.exit),
# so it never serves requests, starts the REPL, or calls the model. Two overrides keep it
# self-contained: the MCP client is disabled entirely, and SOLR_MCP_JAR gets a placeholder value
# so mcp-stdio property binding resolves if it happens at all.
#
# The MCP client must be DISABLED (spring.ai.mcp.client.enabled=false), not merely left
# uninitialized: constructing the client beans spawns plain non-daemon executor pools that survive
# the context close, and the JVM then never exits — and the cache is assembled by a helper the JVM
# spawns AT exit, so the training run hangs forever with no cache written. (Normal runs are
# unaffected: the REPL/web app exits through System.exit paths that don't wait on those pools.)
# The cost is only that MCP/transport classes miss the cache and load the ordinary way.

set -euo pipefail
cd "$(dirname "$0")/.."

TRAINING_PROFILES="${1:-}"

./gradlew bootJar

# The Boot plugin also produces a *-plain.jar next to the executable one; keep only the latter.
jar="$(ls build/libs/solr-mcp-client-*.jar | grep -v -- '-plain\.jar$' | head -1)"
dest=build/aot-cache
rm -rf "$dest"

java -Djarmode=tools -jar "$jar" extract --destination "$dest"

app_jar="$(basename "$jar")"
training_args=(--spring.ai.mcp.client.enabled=false --SOLR_MCP_JAR=/dev/null)
if [[ -n "$TRAINING_PROFILES" ]]; then
    training_args+=("--spring.profiles.active=$TRAINING_PROFILES")
fi

# Success is judged by the cache file, not the exit code: the training JVM exits non-zero even
# after "AOTCache creation is complete", and a failed context refresh would leave no cache.
(
    cd "$dest"
    java --sun-misc-unsafe-memory-access=allow \
        -XX:AOTCacheOutput=app.aot \
        -Dspring.context.exit=onRefresh \
        -jar "$app_jar" "${training_args[@]}"
) || true

if [[ ! -f "$dest/app.aot" ]]; then
    echo "Training run produced no cache — see the output above for the startup failure." >&2
    exit 1
fi

echo
echo "AOT cache written to $dest/app.aot. Launch from the extracted directory, any profiles:"
echo
echo "  cd $dest"
echo "  java --sun-misc-unsafe-memory-access=allow -XX:AOTCache=app.aot -jar $app_jar --spring.profiles.active=cli,mcp-http"
