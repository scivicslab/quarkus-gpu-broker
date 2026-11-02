#!/usr/bin/env bash
# Declares to the GPU broker how many jobs the vLLM server on this node computes at once, so that
# the broker gives it that many slots (DeclaredEndpointConcurrency_261103_oo01).
#
# Run by a start script right after it starts the vLLM container, in the background: it waits
# until the server answers /metrics (a model takes minutes to load), reads the server's start
# time from there, and sends both to the broker. The declaration holds for this run of the server
# only; a server started again without this script falls back to what the broker observes.
#
# Usage: declare-capacity.sh <port> <max-num-seqs> [broker-url]
#   e.g. nohup ~/declare-capacity.sh 8000 32 > ~/declare-capacity.log 2>&1 &
set -uo pipefail
port=$1
max=$2
broker=${3:-http://192.168.5.21:30805}
address="$(hostname -I | awk '{print $1}'):$port"

for _ in $(seq 360); do   # up to 30 minutes, every 5 seconds
    start=$(curl -s --max-time 5 "http://localhost:$port/metrics" \
            | awk '/^process_start_time_seconds /{s=$2} /^vllm:num_requests_running/{v=1} END{if (s && v) print s}')
    if [ -n "$start" ]; then
        code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$broker/capacity/$address" \
               -H 'Content-Type: application/json' \
               -d "{\"maxConcurrency\": $max, \"serverStartTime\": $start}")
        echo "$(date '+%F %T') declared $address max=$max start=$start -> HTTP $code"
        [ "$code" = 204 ] && exit 0
        exit 1
    fi
    sleep 5
done
echo "$(date '+%F %T') $address did not answer /metrics within 30 minutes; nothing declared"
exit 1
