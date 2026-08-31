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
# Answers one question: will the credentials configured for the mcp-http profile get a token that
# this Solr MCP server accepts? It provisions nothing — creating clients belongs to whoever operates
# the identity provider the server trusts — and it makes no assumption about which provider that is.
#
# It reads the same SOLR_MCP_* variables the application reads, so what it checks is what the
# application will send:
#
#   SOLR_MCP_OAUTH_CLIENT_SECRET   required
#   SOLR_MCP_HTTP_URL              default http://localhost:8080
#   SOLR_MCP_HTTP_ENDPOINT         default /mcp
#   SOLR_MCP_OAUTH_TOKEN_URI       default the local Keycloak realm
#   SOLR_MCP_OAUTH_CLIENT_ID       default solr-mcp-service
#   SOLR_MCP_OAUTH_SCOPES          optional, comma-separated
#   SOLR_MCP_OAUTH_AUDIENCE        optional; sent as `audience=` on the token request. Auth0 needs
#                                  this to put the resource URI in `aud`; Keycloak ignores it and
#                                  needs an audience protocol mapper on the client instead.
set -euo pipefail

SOLR_MCP_HTTP_URL=${SOLR_MCP_HTTP_URL:-http://localhost:8080}
SOLR_MCP_HTTP_ENDPOINT=${SOLR_MCP_HTTP_ENDPOINT:-/mcp}
SOLR_MCP_OAUTH_TOKEN_URI=${SOLR_MCP_OAUTH_TOKEN_URI:-http://localhost:8180/realms/solr-mcp/protocol/openid-connect/token}
SOLR_MCP_OAUTH_CLIENT_ID=${SOLR_MCP_OAUTH_CLIENT_ID:-solr-mcp-service}
SOLR_MCP_OAUTH_SCOPES=${SOLR_MCP_OAUTH_SCOPES:-}
SOLR_MCP_OAUTH_AUDIENCE=${SOLR_MCP_OAUTH_AUDIENCE:-}

command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }
[ -n "${SOLR_MCP_OAUTH_CLIENT_SECRET:-}" ] || {
    echo "SOLR_MCP_OAUTH_CLIENT_SECRET is not set — export the secret the application would use" >&2
    exit 1
}

note() { printf '  %s\n' "$1"; }
step() { printf '\n%s\n' "$1"; }
fail() { printf '\n  FAILED: %s\n' "$1" >&2; exit 1; }

# The server is the authority on the audience it demands, so ask it rather than assuming a path.
step "Resource URI the server expects in aud"
RESOURCE=$(curl -sf "$SOLR_MCP_HTTP_URL/.well-known/oauth-protected-resource" | jq -r '.resource // empty' || true)
if [ -n "$RESOURCE" ]; then
    note "$RESOURCE"
else
    RESOURCE="$SOLR_MCP_HTTP_URL$SOLR_MCP_HTTP_ENDPOINT"
    note "$RESOURCE (assumed — the server did not advertise one)"
fi

step "Requesting a client_credentials token from $SOLR_MCP_OAUTH_TOKEN_URI"
TOKEN_ARGS=(-d grant_type=client_credentials
            -d "client_id=$SOLR_MCP_OAUTH_CLIENT_ID"
            -d "client_secret=$SOLR_MCP_OAUTH_CLIENT_SECRET")
[ -n "$SOLR_MCP_OAUTH_SCOPES" ] && TOKEN_ARGS+=(-d "scope=${SOLR_MCP_OAUTH_SCOPES//,/ }")
[ -n "$SOLR_MCP_OAUTH_AUDIENCE" ] && TOKEN_ARGS+=(-d "audience=$SOLR_MCP_OAUTH_AUDIENCE")

RESPONSE=$(curl -s -X POST "$SOLR_MCP_OAUTH_TOKEN_URI" "${TOKEN_ARGS[@]}")
TOKEN=$(echo "$RESPONSE" | jq -r '.access_token // empty')
[ -n "$TOKEN" ] || fail "the grant was refused: $(echo "$RESPONSE" | jq -c '{error, error_description}' 2>/dev/null || echo "$RESPONSE")"
note "issued for client $SOLR_MCP_OAUTH_CLIENT_ID"

step "Checking the aud claim"
# Pad the base64url payload back to a multiple of 4. The guard matters: `seq 0` is not portable —
# GNU prints nothing, BSD counts down and prints "1 0" — and `printf '=%.0s'` with no arguments
# still emits one '='. Unguarded, a payload that is already aligned gains padding, which a strict
# decoder (GNU coreutils base64 requires a length divisible by 4) rejects. Decode errors are not
# silenced: an undecodable payload must not be reported as a missing aud claim, which would send
# the operator to fix an identity provider that is behaving correctly.
CLAIMS=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | {
    read -r p
    pad=$(( (4 - ${#p} % 4) % 4 ))
    [ "$pad" -gt 0 ] && p="$p$(printf '=%.0s' $(seq "$pad"))"
    printf '%s' "$p"
} | base64 -d) || fail "could not decode the token payload — the response may not be a JWT"
note "iss $(echo "$CLAIMS" | jq -r '.iss // "(none)"')"
note "aud $(echo "$CLAIMS" | jq -c '.aud // "(none)"')"
if ! echo "$CLAIMS" | jq -e --arg r "$RESOURCE" '[.aud] | flatten | index($r)' >/dev/null; then
    echo >&2
    echo "  FAILED: the token does not carry $RESOURCE in aud, so the server will answer 401." >&2
    echo "  The identity provider has to put it there; nothing else can." >&2
    echo "    Keycloak — add an audience protocol mapper (oidc-audience-mapper) to the client," >&2
    echo "               with Included Custom Audience set to that URI. It ignores RFC 8707." >&2
    echo "    Auth0    — set SOLR_MCP_OAUTH_AUDIENCE to that URI so it is sent on the request." >&2
    exit 1
fi
note "contains $RESOURCE"

# The claim check is necessary but not sufficient: issuer, signature and expiry are the server's to
# judge, so the last word has to come from the server itself.
step "Calling $SOLR_MCP_HTTP_URL$SOLR_MCP_HTTP_ENDPOINT with it"
BODY='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-service-token","version":"1"}}}'
HTTP_BODY=$(mktemp)
trap 'rm -f "$HTTP_BODY"' EXIT
STATUS=$(curl -s -o "$HTTP_BODY" -w '%{http_code}' -X POST "$SOLR_MCP_HTTP_URL$SOLR_MCP_HTTP_ENDPOINT" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -d "$BODY")

case "$STATUS" in
    200) note "$STATUS — $(sed 's/^[^{]*//' "$HTTP_BODY" | jq -r '.result.serverInfo | "\(.name) \(.version)"' 2>/dev/null || echo 'accepted')" ;;
    401) fail "$STATUS — the server rejected the token. $(grep -o 'error=\"[^\"]*\"[^\"]*\"[^\"]*\"' "$HTTP_BODY" 2>/dev/null || head -c 200 "$HTTP_BODY")" ;;
    403) fail "$STATUS — authenticated but not permitted; check the scopes the server requires" ;;
    *)   fail "$STATUS — $(head -c 200 "$HTTP_BODY")" ;;
esac

printf '\nThe configured credentials work against this server.\n'
