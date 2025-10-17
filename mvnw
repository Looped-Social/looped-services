#!/bin/sh
# Apache Maven Wrapper startup script (simplified)
# Requires: Java installed. Downloads wrapper JAR on first run.

set -e

BASE_DIR=$(cd "$(dirname "$0")"; pwd -P)
WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
PROPS_FILE="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"

die() { echo "[mvnw] $1" 1>&2; exit 1; }

if [ ! -f "$PROPS_FILE" ]; then
  die "Missing $PROPS_FILE"
fi

WRAPPER_URL=$(grep -E '^wrapperUrl=' "$PROPS_FILE" | cut -d'=' -f2- | tr -d '\r')
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "[mvnw] Downloading Maven Wrapper JAR..."
  mkdir -p "$(dirname "$WRAPPER_JAR")"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR" || die "Failed to download wrapper jar via curl"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR" || die "Failed to download wrapper jar via wget"
  else
    die "Please install curl or wget to download the wrapper jar"
  fi
fi

# Use JAVA_HOME if set
if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

exec "$JAVA_CMD" \
  -Dmaven.multiModuleProjectDirectory="$BASE_DIR" \
  ${MAVEN_OPTS} \
  -cp "$WRAPPER_JAR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"

