#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")"

if ! command -v hyperfine >/dev/null 2>&1; then
  echo "hyperfine is required for this benchmark." >&2
  exit 1
fi

SOURCE_LAUNCHER="${SOURCE_LAUNCHER:-../micronaut/micronaut}"
RUNS="${RUNS:-20}"
WARMUP="${WARMUP:-3}"

if [ ! -x "$SOURCE_LAUNCHER" ]; then
  echo "Missing source launcher: $SOURCE_LAUNCHER" >&2
  echo "Build it with: (cd ../micronaut && ./build.sh)" >&2
  exit 1
fi

mvn -q -DskipTests test-compile >/dev/null

hyperfine --warmup "$WARMUP" --runs "$RUNS" \
  "sh -c 'touch ../micronaut/tests/hello/HelloControllerTest.java && $SOURCE_LAUNCHER --test --port 0 ../micronaut/examples/hello -- ../micronaut/tests/hello'" \
  "sh -c 'touch src/test/java/examples/hello/HelloControllerTest.java && mvn -q test'"
