# Crema source launcher demo

This demo compares a native Crema source launcher with the regular Java source
launcher for the same single-file Java program.

## Requirements

- macOS on Apple Silicon
- GraalVM JDK 25 available through `JAVA_HOME`
- `native-image` available at `$JAVA_HOME/bin/native-image` when building the
  launcher locally
- Optional: `hyperfine` for repeatable benchmark numbers

## Prepare the launcher

This repository intentionally does not include binary artifacts. Build the demo
launcher locally:

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
- Generated files such as `crema`, `script`, `*.class`, archives, profile data,
  and Native Image build outputs are ignored.
