#!/bin/sh
set -eu

mvn -q package dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
if [ "$#" -eq 0 ]; then
    set -- HelloController.java
fi

java -cp "target/classes:$(cat target/runtime-classpath.txt)" demo.MicronautSourceLauncher "$@"
