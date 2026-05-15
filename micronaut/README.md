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

For the native launcher, embedded Micronaut and compiler dependencies are
indexed from the image resources and served to `javac` through an in-memory file
manager. The older disk extraction path is kept as a fallback for development
and diagnostics.

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

This first builds `./micronaut-profiled` with sampled PGO collection, then runs
several fresh startup-only training launches against `examples/hello`. By
default, each training launch is stopped immediately after the startup line is
printed, so the collected profiles are biased toward source extraction, javac
parsing and annotation processing, and Micronaut bootstrap rather than
steady-state request handling. The script writes startup profiles under
`target/pgo/` and then builds the final optimized `./micronaut` executable.

The default training run count is 12. Override it with `PGO_TRAIN_RUNS=...`.
If you also want the profile to include request handling, set
`PGO_TRAIN_REQUESTS=...`; the default is `0`.

For faster development, run the launcher on the JVM:

```sh
./run-jvm.sh examples/hello
```

## Run

Minimal single-file controller:

```java
package demo;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

@Controller("/hello")
public final class HelloController {
    @Get
    @Produces(MediaType.TEXT_PLAIN)
    String index() {
        return "hello world";
    }
}
```

Run that file directly:

```sh
./micronaut HelloController.java
curl http://localhost:8080/hello
```

Or run one of the checked-in example directories:

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

## Source Tests

The launcher can also run a small Micronaut-style source test. Test mode
compiles the application sources and test sources, starts the Micronaut server,
injects `@Client("/") HttpClient` fields, and runs the tests through the real
JUnit Platform launcher and Jupiter engine. For this demo, `@MicronautTest` is
a small launcher-provided adapter that wires the running source-launched server
into JUnit; the JUnit annotations and assertions are the real JUnit API.

```java
package examples.hello;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
final class HelloControllerTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void returnsHelloWorld() {
        String response = client.toBlocking().retrieve("/hello");

        assertEquals("hello world", response);
    }
}
```

Run the checked-in test from source:

```sh
./micronaut --test examples/hello -- examples/hello-test
```

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
