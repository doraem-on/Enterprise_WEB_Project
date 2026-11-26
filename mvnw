#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_VERSION=3.9.9
TOOLS="$ROOT/.tools"
MAVEN="$TOOLS/apache-maven-$MAVEN_VERSION/bin/mvn"
if [ ! -x "$MAVEN" ]; then
 mkdir -p "$TOOLS"
 curl -fL --retry 3 "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/apache-maven-$MAVEN_VERSION-bin.tar.gz" -o "$TOOLS/maven.tar.gz"
 EXPECTED=7a9cdf674fc1703d6382f5f330b3d110ea1b512b51f1652846d9e4e8a588d766
 ACTUAL=$(shasum -a 256 "$TOOLS/maven.tar.gz" | cut -d ' ' -f 1)
 [ "$EXPECTED" = "$ACTUAL" ] || { echo 'Maven checksum mismatch' >&2; exit 1; }
 tar -xzf "$TOOLS/maven.tar.gz" -C "$TOOLS"
fi
exec "$MAVEN" "-Dmaven.repo.local=$ROOT/.tools/repository" "$@"
