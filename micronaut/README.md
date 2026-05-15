# Micronaut source launcher demo

This demo builds a native launcher named `micronaut`. The launcher has the
Micronaut runtime, Netty server, HTTP modules, `javac`, and the Micronaut
annotation processor baked into the native executable. At launch time it
compiles a source controller and starts an embedded Micronaut server.

The dynamically supplied controller is compiled on launch. The Micronaut
framework pieces are available from the launcher, with Micronaut initialized at
runtime and its packages preserved for runtime-loaded application classes.

## Requirements

- macOS on Apple Silicon
- GraalVM JDK 25 available through `JAVA_HOME`
- `native-image` available at `$JAVA_HOME/bin/native-image`
- Maven

## Build

```sh
./build.sh
```

The generated `./micronaut` launcher is ignored by Git.

For faster development, run the launcher on the JVM:

```sh
./run-jvm.sh HelloController.java
```

## Run

```sh
./micronaut HelloController.java
curl http://localhost:8080/hello
```

Expected response:

```text
hello world
```

Use another port if needed:

```sh
./micronaut --port 9090 HelloController.java
```
