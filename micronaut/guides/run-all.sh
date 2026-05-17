#!/usr/bin/env sh
set -eu

cd "$(dirname "$0")/.."

for guide in \
  creating-first-app \
  dependency-injection \
  configuration-properties \
  validation \
  http-client \
  static-resources \
  data-jdbc-sqlite \
  error-handling \
  content-negotiation \
  cors \
  server-filter-request \
  scheduled
do
  echo "== $guide =="
  ./run-jvm.sh --test --port 0 "guides/$guide/src" -- "guides/$guide/test"
done
