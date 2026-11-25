#!/usr/bin/env bash
# Declares to the GPU broker how many jobs the vLLM server on this node computes at once, so that
# the broker gives it that many slots (DeclaredEndpointConcurrency_261103_oo01).
#
# Run by a start script right after it starts the vLLM container, in the background: it waits
# until the server answers /metrics (a model takes minutes to load), reads the server's start
# time from there, and sends both to the broker. The declaration holds for this run of the server
# only; a server started again without this script falls back to what the broker observes.
#
# PUT /capacity needs a token from the Keycloak realm w206-infra (ManagementAuthentication_261115_oo01).
# This host holds one client of that realm. Right before the PUT, the script gets a token with the
# client-credentials grant; the token lives five minutes and is not kept.
#
#   ~/.config/gpu-broker/capacity-client.secret   mode 600, two lines: client_id=... client_secret=...
#   ~/.config/gpu-broker/keycloak-ca.pem          the certificate of https://192.168.5.25 (self-signed), public
#
# The secret file is made by create-infra-realm.py (k8s-pups-private-overlays/ops) and copied here by
# the operator; it is never put in a repository, an argument list or a log. The script refuses a secret
# file that other users can read. The Keycloak certificate is checked against keycloak-ca.pem, not skipped.
#
# Usage: declare-capacity.sh <port> <max-num-seqs> [broker-url]
#   e.g. nohup ~/declare-capacity.sh 8000 32 > ~/declare-capacity.log 2>&1 &
# Environment (all optional): GPU_BROKER_CONFIG_DIR (default ~/.config/gpu-broker), KEYCLOAK_TOKEN_URL,
# TOKEN_ATTEMPTS (default 6), TOKEN_RETRY_SECONDS (default 10), METRICS_WAIT_SECONDS (default 5),
# START_EPOCH_COMMAND (a command printing the server process's start time in epoch seconds, for
# servers with no vLLM-style /metrics, e.g. Ollama; it replaces the /metrics poll).
set -uo pipefail
port=$1
max=$2
broker=${3:-http://192.168.5.21:30805}
address="$(hostname -I | awk '{print $1}'):$port"

conf=${GPU_BROKER_CONFIG_DIR:-$HOME/.config/gpu-broker}
secret_file=$conf/capacity-client.secret
ca_file=$conf/keycloak-ca.pem
token_url=${KEYCLOAK_TOKEN_URL:-https://192.168.5.25/pups-with-audit-auth/realms/w206-infra/protocol/openid-connect/token}
attempts=${TOKEN_ATTEMPTS:-6}
retry=${TOKEN_RETRY_SECONDS:-10}
metrics_wait=${METRICS_WAIT_SECONDS:-5}

log() { echo "$(date '+%F %T') $*"; }
elog() { log "$@" >&2; }   # inside $(...), stdout is the token

# Prints an access token, or nothing. Failures are logged without any secret.
fetch_token() {
    if [ ! -f "$secret_file" ]; then elog "no $secret_file; nothing declared"; return 1; fi
    local mode owner
    mode=$(stat -c '%a' "$secret_file"); owner=$(stat -c '%u' "$secret_file")
    if [ "$mode" != 600 ] || [ "$owner" != "$(id -u)" ]; then
        elog "$secret_file must be mode 600 and owned by this user (it is mode $mode); nothing declared"; return 1
    fi
    if [ ! -f "$ca_file" ]; then elog "no $ca_file to check the Keycloak certificate with; nothing declared"; return 1; fi
    local id secret reply token n
    id=$(sed -n 's/^client_id=//p' "$secret_file" | head -n 1)
    secret=$(sed -n 's/^client_secret=//p' "$secret_file" | head -n 1)
    if [ -z "$id" ] || [ -z "$secret" ]; then elog "$secret_file lacks client_id or client_secret; nothing declared"; return 1; fi
    for n in $(seq "$attempts"); do
        # The secret travels on standard input, not in the argument list that ps shows.
        reply=$(printf 'grant_type=client_credentials&client_id=%s&client_secret=%s' "$id" "$secret" \
                | curl -s --max-time 15 --cacert "$ca_file" -d @- "$token_url")
        token=$(printf '%s' "$reply" | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
        if [ -n "$token" ]; then printf '%s' "$token"; return 0; fi
        elog "no token from Keycloak (attempt $n of $attempts)"
        [ "$n" -lt "$attempts" ] && sleep "$retry"
    done
    return 1
}

for _ in $(seq 360); do   # up to 30 minutes, every 5 seconds
    if [ -n "${START_EPOCH_COMMAND:-}" ]; then
        # A server with no vLLM-style /metrics (e.g. Ollama): the start time comes from a command,
        # whose single output line is the process start in epoch seconds. Empty while the process
        # the command looks for is not up yet, so the loop waits for it as the metrics path does.
        start=$($START_EPOCH_COMMAND 2>/dev/null)
    else
        start=$(curl -s --max-time 5 "http://localhost:$port/metrics" \
                | awk '/^process_start_time_seconds /{s=$2} /^vllm:num_requests_running/{v=1} END{if (s && v) print s}')
    fi
    if [ -n "$start" ]; then
        token=$(fetch_token) || { log "$address: could not get a token; nothing declared"; exit 1; }
        # The token goes to curl as a config on standard input, not as an argument.
        code=$(printf 'header = "Authorization: Bearer %s"\n' "$token" \
               | curl -s -K - -o /dev/null -w '%{http_code}' -X PUT "$broker/capacity/$address" \
                 -H 'Content-Type: application/json' \
                 -d "{\"maxConcurrency\": $max, \"serverStartTime\": $start}")
        log "declared $address max=$max start=$start -> HTTP $code"
        [ "$code" = 204 ] && exit 0
        exit 1
    fi
    sleep "$metrics_wait"
done
log "$address did not answer /metrics within 30 minutes; nothing declared"
exit 1
