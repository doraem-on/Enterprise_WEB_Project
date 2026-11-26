#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
 export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
export DATABASE_USER="${DATABASE_USER:-$(id -un)}"
python3 scripts/download_models.py
./mvnw -q -DskipTests package
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar target/reconnect-1.0.0.jar
