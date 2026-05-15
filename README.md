# Crema source launcher demo

This demo compares a native Crema source launcher with the regular Java source
launcher for the same single-file Java program.

## Requirements

- GraalVM with the public-member metadata preservation support available through
  `JAVA_HOME`. This demo was tested with Oracle GraalVM `25.1.0-dev+10.1`
  (`25.0.2+10-LTS-jvmci-25.1-b17`).
- `native-image` available at `$JAVA_HOME/bin/native-image` when building the
  launcher locally
- Optional: `hyperfine` for repeatable benchmark numbers

## Prepare the launcher

This repository intentionally does not include binary artifacts. Build the demo
launcher locally with the tested GraalVM:

```sh
(cd building && "$JAVA_HOME/bin/native-image" -ea \
  -H:+UnlockExperimentalVMOptions \
  -o ../crema \
  -H:+RuntimeClassLoading \
  -H:Preserve=package=java.util \
  -H:Preserve=package=java.lang \
  -H:Preserve=package=java.io \
  -H:-InterpreterTraceSupport \
  -H:+AllowJRTFileSystem \
  -H:ConfigurationFileDirectories=. \
  --initialize-at-run-time=com.sun.tools.javac.file.Locations \
  --initialize-at-build-time=com.sun.tools.doclint,'com.sun.tools.javac.parser.Tokens$TokenKind','com.sun.tools.javac.parser.Tokens$Token$Tag' \
  com.sun.tools.javac.launcher.SourceLauncher)
```

For older GraalVM builds that still need the local `jdk.internal.misc.VM`
substitution, use the helper script instead:

```sh
(cd building && ./build-crema-srclauncher.sh -o ../crema)
```

If you already have a compatible Crema launcher, place it at `./crema`. The
file is ignored by Git.

## Run

```sh
./crema -Djava.home="$JAVA_HOME" script.java
java script.java
```

Both commands should print:

```text
Hello, Devoxx UK 2026!
```

## Benchmark

```sh
hyperfine --warmup 3 --runs 20 \
  './crema -Djava.home=$JAVA_HOME script.java' \
  'java script.java'
```

Sample result on this machine:

```text
./crema -Djava.home=$JAVA_HOME script.java ran
  7.62 +/- 0.93 times faster than java script.java
```

## Repository contents

- `README.md` and `script.java` are the main demo.
- `building/` is an optional build workbench for the native source launcher.
- `micronaut/` is a second demo that builds a native `micronaut` launcher with
  Micronaut, Netty, `javac`, and the Micronaut annotation processor baked in,
  then compiles and runs a source controller at launch time.
- Generated files such as `crema`, `script`, `*.class`, archives, profile data,
  and Native Image build outputs are ignored.
