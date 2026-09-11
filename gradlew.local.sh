#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME="${JAVA_HOME:-/Volumes/DevDisk/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/Volumes/DevDisk/Library/Android/sdk}"
exec "$DIR/gradlew" "$@"
