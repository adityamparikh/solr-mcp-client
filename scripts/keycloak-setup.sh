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
# Provisions the Keycloak realm, confidential client and audience mapper that the mcp-http profile
# needs, then proves the result by requesting a token and inspecting its `aud` claim.
#
# Idempotent: every step is skipped if it already exists, so this is safe to re-run. Nothing that
# already exists is modified, with one exception — an audience mapper pointing at the wrong resource
# URI is corrected, since that is the failure this script exists to prevent.
#
#   ./scripts/keycloak-setup.sh
#
# Override any of these:
#   KEYCLOAK_URL              default http://localhost:8180
#   KEYCLOAK_ADMIN            default admin
#   KEYCLOAK_ADMIN_PASSWORD   default admin
#   REALM                     default solr-mcp
#   CLIENT_ID                 default solr-mcp-server
#   SOLR_MCP_URL              default http://localhost:8080
set -euo pipefail

KEYCLOAK_URL=${KEYCLOAK_URL:-http://localhost:8180}
KEYCLOAK_ADMIN=${KEYCLOAK_ADMIN:-admin}
KEYCLOAK_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin}
REALM=${REALM:-solr-mcp}
CLIENT_ID=${CLIENT_ID:-solr-mcp-server}
SOLR_MCP_URL=${SOLR_MCP_URL:-http://localhost:8080}

command -v jq >/dev/null || { echo "jq is required (brew install jq)" >&2; exit 1; }

note() { printf '  %s\n' "$1"; }
step() { printf '\n%s\n' "$1"; }

step "Keycloak at $KEYCLOAK_URL"
if ! curl -sf "$KEYCLOAK_URL/realms/master/.well-known/openid-configuration" >/dev/null; then
    echo "  not reachable. Start it with:" >&2
    echo "    docker run -d --name keycloak -p 8180:8080 \\" >&2
    echo "      -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \\" >&2
    echo "      quay.io/keycloak/keycloak:26.0 start-dev" >&2
    exit 1
fi

ADMIN_TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
    -d client_id=admin-cli -d "username=$KEYCLOAK_ADMIN" -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
    -d grant_type=password | jq -r '.access_token // empty')
[ -n "$ADMIN_TOKEN" ] || { echo "  admin login failed for '$KEYCLOAK_ADMIN'" >&2; exit 1; }
note "authenticated as $KEYCLOAK_ADMIN"

kc() { curl -s -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" "$@"; }

# The server is the authority on its own resource URI, so ask it rather than assuming /mcp. It is
# only advertised once security is configured, hence the fallback for a server that is not up yet.
step "Resource URI (the value the token's aud must carry)"
AUDIENCE=$(curl -sf "$SOLR_MCP_URL/.well-known/oauth-protected-resource" | jq -r '.resource // empty' || true)
if [ -n "$AUDIENCE" ]; then
    note "$AUDIENCE (from the running server)"
else
    AUDIENCE="$SOLR_MCP_URL/mcp"
    note "$AUDIENCE (assumed — $SOLR_MCP_URL did not answer; re-run once it is up to confirm)"
fi

step "Realm '$REALM'"
if kc "$KEYCLOAK_URL/admin/realms/$REALM" | jq -e '.realm' >/dev/null 2>&1; then
    note "already exists"
else
    kc -X POST "$KEYCLOAK_URL/admin/realms" -d "{\"realm\":\"$REALM\",\"enabled\":true}" >/dev/null
    note "created"
fi

step "Client '$CLIENT_ID'"
CLIENT_UUID=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" | jq -r '.[0].id // empty')
if [ -n "$CLIENT_UUID" ]; then
    note "already exists"
    PUBLIC=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" | jq -r '.publicClient')
    SERVICE_ACCOUNTS=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" | jq -r '.serviceAccountsEnabled')
    [ "$PUBLIC" = "false" ] || note "WARNING: it is a public client, so it cannot use client_credentials"
    [ "$SERVICE_ACCOUNTS" = "true" ] || note "WARNING: service accounts are disabled, so client_credentials will fail"
else
    kc -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" -d "{
            \"clientId\": \"$CLIENT_ID\",
            \"publicClient\": false,
            \"serviceAccountsEnabled\": true,
            \"standardFlowEnabled\": false,
            \"directAccessGrantsEnabled\": false
        }" >/dev/null
    CLIENT_UUID=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" | jq -r '.[0].id')
    note "created as a confidential service-account client"
fi

# Keycloak does not honour the RFC 8707 `resource=` parameter, so without this mapper the token is
# issued normally and the MCP server then refuses it with 401.
step "Audience mapper"
MAPPERS=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models")
EXISTING_ID=$(echo "$MAPPERS" | jq -r --arg aud "$AUDIENCE" \
    '.[] | select(.protocolMapper == "oidc-audience-mapper" and .config."included.custom.audience" == $aud) | .id' | head -1)
STALE_ID=$(echo "$MAPPERS" | jq -r '.[] | select(.name == "mcp-audience") | .id' | head -1)

MAPPER_BODY="{
        \"name\": \"mcp-audience\",
        \"protocol\": \"openid-connect\",
        \"protocolMapper\": \"oidc-audience-mapper\",
        \"config\": {
            \"included.custom.audience\": \"$AUDIENCE\",
            \"access.token.claim\": \"true\",
            \"id.token.claim\": \"false\",
            \"introspection.token.claim\": \"true\"
        }
    }"

if [ -n "$EXISTING_ID" ]; then
    note "already maps aud to $AUDIENCE"
elif [ -n "$STALE_ID" ]; then
    kc -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models/$STALE_ID" \
        -d "$(echo "$MAPPER_BODY" | jq --arg id "$STALE_ID" '. + {id: $id}')" >/dev/null
    note "corrected an existing mcp-audience mapper to $AUDIENCE"
else
    kc -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
        -d "$MAPPER_BODY" >/dev/null
    note "created, mapping aud to $AUDIENCE"
fi

step "Verifying a real token"
SECRET=$(kc "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" | jq -r '.value // empty')
[ -n "$SECRET" ] || { echo "  could not read the client secret" >&2; exit 1; }

TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    -d grant_type=client_credentials -d "client_id=$CLIENT_ID" -d "client_secret=$SECRET" \
    | jq -r '.access_token // empty')
[ -n "$TOKEN" ] || { echo "  the client_credentials grant failed" >&2; exit 1; }

CLAIMS=$(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | { read -r p; printf '%s' "$p$(printf '=%.0s' $(seq $(( (4 - ${#p} % 4) % 4 ))))"; } | base64 -d 2>/dev/null)
if echo "$CLAIMS" | jq -e --arg aud "$AUDIENCE" '[.aud] | flatten | index($aud)' >/dev/null; then
    note "token carries aud $AUDIENCE"
else
    echo "  token does NOT carry aud $AUDIENCE — the MCP server will answer 401" >&2
    echo "  claims: $(echo "$CLAIMS" | jq -c '{iss, aud, azp}')" >&2
    exit 1
fi

cat <<EOF

Ready. Run the client against it with:

  export SOLR_MCP_OAUTH_CLIENT_SECRET=$SECRET
  ./gradlew bootRun --args='--spring.profiles.active=mcp-http --server.port=8090'

The secret above belongs to a local development realm. Treat a real one as a secret: keep it in the
environment, never in committed configuration.
EOF
