#!/bin/sh
set -eu

PORT="${PGO_TRAIN_PORT:-8099}"
PROFILE_DIR="${PGO_DIR:-target/pgo}"
PROFILE_FILE="${PGO_PROFILE:-$PROFILE_DIR/hello.iprof}"
TRAIN_LOG="$PROFILE_DIR/train.log"

mkdir -p "$PROFILE_DIR"
PROFILE_FILE="$(cd "$(dirname "$PROFILE_FILE")" && pwd)/$(basename "$PROFILE_FILE")"
rm -f "$PROFILE_FILE" "$TRAIN_LOG"

mvn -Ppgo-sampling native:compile
cp target/micronaut-profiled micronaut-profiled

./micronaut-profiled -XX:ProfilesDumpFile="$PROFILE_FILE" --port "$PORT" examples/hello > "$TRAIN_LOG" 2>&1 &
server_pid=$!

cleanup() {
    if kill -0 "$server_pid" 2>/dev/null; then
        kill "$server_pid" 2>/dev/null || true
        wait "$server_pid" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

started=false
for _ in $(seq 1 120); do
    if grep -q "Micronaut source launcher started" "$TRAIN_LOG"; then
        started=true
        break
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
        cat "$TRAIN_LOG" >&2
        exit 1
    fi
    sleep 0.5
done

if [ "$started" != "true" ]; then
    cat "$TRAIN_LOG" >&2
    echo "Timed out waiting for profiled launcher to start." >&2
    exit 1
fi

curl -fsS "http://localhost:$PORT/hello" >/dev/null
kill "$server_pid"
wait "$server_pid" 2>/dev/null || true
trap - EXIT INT TERM

if [ ! -s "$PROFILE_FILE" ]; then
    cat "$TRAIN_LOG" >&2
    echo "PGO profile was not written: $PROFILE_FILE" >&2
    exit 1
fi

mvn -Ppgo -Dpgo.profile="$PROFILE_FILE" native:compile
cp target/micronaut-pgo micronaut

echo "Built PGO-optimized launcher: ./micronaut"
echo "Profile: $PROFILE_FILE"
