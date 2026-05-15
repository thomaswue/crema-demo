#!/bin/bash

# Exit on error
set -e

if [[ -z "$JAVA_HOME" ]]; then
    echo "JAVA_HOME is not set"
    exit 1
fi

echo "Java Home:" "$JAVA_HOME"

echo "Building substitutions"
mkdir -p subst-out
$JAVA_HOME/bin/javac --source-path subst-src -d subst-out subst-src/Target_jdk_internal_misc_VM.java

echo "Building crema-srclauncher native image..."
"$JAVA_HOME/bin/native-image" -ea \
  -cp subst-out \
  -H:+UnlockExperimentalVMOptions \
  -o crema-srclauncher \
  `# Crema options` \
  -H:+RuntimeClassLoading \
  -H:Preserve=package=java.util \
  -H:Preserve=package=java.lang \
  -H:Preserve=package=java.io \
  -H:-InterpreterTraceSupport \
  `# Native Image AOT config for javac` \
  -H:+AllowJRTFileSystem \
  -H:ConfigurationFileDirectories=. \
  --initialize-at-run-time=com.sun.tools.javac.file.Locations \
  --initialize-at-build-time=com.sun.tools.doclint,'com.sun.tools.javac.parser.Tokens$TokenKind','com.sun.tools.javac.parser.Tokens$Token$Tag' \
  `# --pgo-instrument` \
  `# --pgo` \
  $@ \
  com.sun.tools.javac.launcher.SourceLauncher

echo "Done!"
