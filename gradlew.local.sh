#!/bin/sh
# Duiyi needs a Gradle daemon JDK of 17-21, for two reasons that are easy to confuse:
#   - Kotlin 2.0.21 cannot even parse a newer version: "IllegalArgumentException: 25.0.2"
#     out of JavaVersion.parse, with no other explanation.
#   - AGP 8.7.2 is only tested up to ~JDK 21.
# Android Studio's bundled JBR is now 25, so do not default to it blindly: prefer a
# provisioned 17/21 when one exists, and warn instead of failing cryptically if not.
DIR=$(cd "$(dirname "$0")" && pwd)
export ANDROID_HOME="${ANDROID_HOME:-/Volumes/DevDisk/Library/Android/sdk}"

if [ -z "$JAVA_HOME" ]; then
  for candidate in "$HOME"/.gradle/jdks/*/jdk-17*/Contents/Home \
                   "$HOME"/.gradle/jdks/*/jdk-21*/Contents/Home \
                   /Library/Java/JavaVirtualMachines/*17*/Contents/Home \
                   /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
    if [ -x "$candidate/bin/java" ]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
export JAVA_HOME="${JAVA_HOME:-/Volumes/DevDisk/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

if ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE '"(1\.)?(1[0-9]|2[01])'; then
  echo "warning: JAVA_HOME=$JAVA_HOME is not JDK 17-21." >&2
  echo "         Kotlin 2.0.21 will fail with 'IllegalArgumentException: <version>'." >&2
fi

exec "$DIR/gradlew" "$@"
