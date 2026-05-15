# Micronaut source launcher demo

This demo builds a native launcher named `micronaut`. The launcher has the
Micronaut runtime, Netty server, HTTP modules, `javac`, and the Micronaut
annotation processor baked into the native executable. At launch time it
compiles source files and starts an embedded Micronaut server.

The dynamically supplied application sources are compiled on launch. The
Micronaut framework pieces are available from the launcher, with Micronaut
initialized at runtime and its packages preserved for runtime-loaded
application classes.

The launcher also bakes in Micronaut validation, the declarative HTTP client,
and Jackson support so guide-style examples can run from source.

## Requirements

- macOS on Apple Silicon
- GraalVM JDK 25.1 with public-member metadata preservation support available
  through `JAVA_HOME`; this demo was tested with Oracle GraalVM
  `25.1.0-dev+10.1`
- `native-image` available at `$JAVA_HOME/bin/native-image`
- Maven

## Build

```sh
./build.sh
```

The generated `./micronaut` launcher is ignored by Git.

To build a PGO-optimized launcher trained on the hello world controller:

```sh
./build-pgo.sh
```

This first builds `./micronaut-profiled` with sampled PGO collection, runs it
against `examples/hello`, writes a sampling profile under `target/pgo/`, and
then builds the final optimized `./micronaut` executable.

On the test machine, a paired 12-run hello controller benchmark showed a small
startup improvement from sampled PGO: 177.4 ms average for the regular native
launcher and 173.4 ms for the PGO launcher. Excluding the first two runs of
each executable, the averages were 173.6 ms and 172.1 ms.

For faster development, run the launcher on the JVM:

```sh
./run-jvm.sh examples/hello
```

## Run

```sh
./micronaut examples/hello
curl http://localhost:8080/hello
```

Expected response:

```text
hello world
```

Use another port if needed:

```sh
./micronaut --port 9090 examples/hello
```

The launcher accepts one or more Java source files or directories. Directories
are scanned recursively, so multi-file examples work without a local build
tool. Runtime configuration can be supplied with `--property key=value` or
`-Dkey=value`.

## Examples

```sh
./micronaut examples/hello
curl http://localhost:8080/hello

./micronaut --port 8081 examples/parameters
curl http://localhost:8081/greet/Ada

./micronaut --port 8082 examples/json
curl http://localhost:8082/message/crema

./micronaut --port 8083 examples/injection
curl http://localhost:8083/injection/Grace

./micronaut --port 8084 \
  --property warehouse.name=Crema \
  --property warehouse.limits.max-items=42 \
  --property warehouse.limits.refrigerated=true \
  examples/configuration
curl http://localhost:8084/warehouse/summary

./micronaut --port 8085 examples/client
curl http://localhost:8085/client/greet/Turing

./micronaut --port 8086 examples/validation
curl -H 'Content-Type: application/json' \
  -d '{"title":"Native Micronaut","pages":123}' \
  http://localhost:8086/books
```

The examples cover a plain text controller, path variables, JSON serialization,
dependency injection across multiple source files, configuration properties,
declarative HTTP clients, and validation of JSON request bodies.

All examples can also be launched together:

```sh
./micronaut --port 8080 \
  --property warehouse.name=Crema \
  --property warehouse.limits.max-items=42 \
  --property warehouse.limits.refrigerated=true \
  examples
```

By default, the launcher infers Micronaut annotation-processing packages from
the source files. Use `--package demo` or `--package com.example,com.other`
to override that inference. Micronaut itself rejects beans in the default Java
package, so source controllers must use a named package.

Native Image is configured to preserve `java.base` broadly, along with the
compiler, Micronaut, validation, Jakarta, ASM, and JavaParser packages needed by
runtime-loaded source applications. This makes the demo more flexible, at the
cost of a larger native launcher.
