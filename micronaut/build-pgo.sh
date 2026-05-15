#!/bin/sh
set -eu

PORT="${PGO_TRAIN_PORT:-8099}"
TRAIN_RUNS="${PGO_TRAIN_RUNS:-12}"
TRAIN_REQUESTS="${PGO_TRAIN_REQUESTS:-0}"
PROFILE_DIR="${PGO_DIR:-target/pgo}"
PROFILE_NAME="${PGO_PROFILE_NAME:-startup}"
PROFILE_SET="$PROFILE_DIR/$PROFILE_NAME.profiles"
TRAIN_LOG_DIR="$PROFILE_DIR/logs"

case "$TRAIN_RUNS" in
    ''|*[!0-9]*)
        echo "PGO_TRAIN_RUNS must be a positive integer: $TRAIN_RUNS" >&2
        exit 1
        ;;
esac
if [ "$TRAIN_RUNS" -lt 1 ]; then
    echo "PGO_TRAIN_RUNS must be at least 1." >&2
    exit 1
fi

case "$TRAIN_REQUESTS" in
    ''|*[!0-9]*)
        echo "PGO_TRAIN_REQUESTS must be a non-negative integer: $TRAIN_REQUESTS" >&2
        exit 1
        ;;
esac

mkdir -p "$PROFILE_DIR"
PROFILE_DIR="$(cd "$PROFILE_DIR" && pwd)"
PROFILE_SET="$PROFILE_DIR/$PROFILE_NAME.profiles"
TRAIN_LOG_DIR="$PROFILE_DIR/logs"
mkdir -p "$TRAIN_LOG_DIR"
rm -f "$PROFILE_SET" "$PROFILE_DIR/$PROFILE_NAME-"*.iprof "$TRAIN_LOG_DIR/$PROFILE_NAME-"*.log

mvn -Ppgo-sampling native:compile
cp target/micronaut-profiled micronaut-profiled

profile_files=""
run=1
while [ "$run" -le "$TRAIN_RUNS" ]; do
    run_id="$(printf "%02d" "$run")"
    run_port=$((PORT + run - 1))
    profile_file="$PROFILE_DIR/$PROFILE_NAME-$run_id.iprof"
    train_log="$TRAIN_LOG_DIR/$PROFILE_NAME-$run_id.log"

    ./micronaut-profiled -XX:ProfilesDumpFile="$profile_file" --port "$run_port" examples/hello > "$train_log" 2>&1 &
    server_pid=$!

    cleanup() {
        if kill -0 "$server_pid" 2>/dev/null; then
            kill "$server_pid" 2>/dev/null || true
            wait "$server_pid" 2>/dev/null || true
        fi
    }
    trap cleanup EXIT INT TERM

    started=false
    for _ in $(seq 1 600); do
        if grep -q "Micronaut source launcher started" "$train_log"; then
            started=true
            break
        fi
        if ! kill -0 "$server_pid" 2>/dev/null; then
            cat "$train_log" >&2
            exit 1
        fi
        sleep 0.1
    done

    if [ "$started" != "true" ]; then
        cat "$train_log" >&2
        echo "Timed out waiting for profiled launcher to start." >&2
        exit 1
    fi

    request=1
    while [ "$request" -le "$TRAIN_REQUESTS" ]; do
        curl -fsS "http://localhost:$run_port/hello" >/dev/null
        request=$((request + 1))
    done

    kill "$server_pid"
    wait "$server_pid" 2>/dev/null || true
    trap - EXIT INT TERM

    if [ ! -s "$profile_file" ]; then
        cat "$train_log" >&2
        echo "PGO profile was not written: $profile_file" >&2
        exit 1
    fi

    if [ -z "$profile_files" ]; then
        profile_files="$profile_file"
    else
        profile_files="$profile_files,$profile_file"
    fi

    echo "Collected startup PGO profile $run/$TRAIN_RUNS: $profile_file"
    run=$((run + 1))
done

printf '%s\n' "$profile_files" > "$PROFILE_SET"

mvn -Ppgo -Dpgo.profile="$profile_files" native:compile
cp target/micronaut-pgo micronaut

echo "Built PGO-optimized launcher: ./micronaut"
echo "Profiles: $PROFILE_SET"
