# Micronaut source launcher demo

This demo builds a native launcher named `micronaut`. The launcher has the
Micronaut runtime, Netty server, HTTP modules, `javac`, and the Micronaut
annotation processor baked into the native executable. At launch time it
compiles source files and starts an embedded Micronaut server.

The dynamically supplied application sources are compiled on launch. The
Micronaut framework pieces are available from the launcher, with Micronaut
initialized at runtime and its packages preserved for runtime-loaded
application classes.

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
tool.

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
```

The examples cover a plain text controller, path variables, JSON serialization,
and dependency injection across multiple source files.

By default, the launcher infers Micronaut annotation-processing packages from
the source files. Use `--package demo` or `--package com.example,com.other`
to override that inference. Micronaut itself rejects beans in the default Java
package, so source controllers must use a named package.
