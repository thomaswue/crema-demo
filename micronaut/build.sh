#!/bin/sh
set -eu

mvn native:compile
cp target/micronaut micronaut
