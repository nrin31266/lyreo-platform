#!/bin/sh
set -eu
MVNW_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$MVNW_DIR/.mvn/wrapper/maven-wrapper.properties"
URL=$(sed -n 's/^distributionUrl=//p' "$PROPS")
VERSION=3.9.14
CACHE="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/apache-maven-$VERSION"
MAVEN_HOME="$CACHE/apache-maven-$VERSION"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  mkdir -p "$CACHE"
  ZIP="$CACHE/apache-maven-$VERSION-bin.zip"
  echo "Downloading Maven $VERSION..." >&2
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$ZIP"; else wget -q "$URL" -O "$ZIP"; fi
  (cd "$CACHE" && unzip -q -o "$ZIP")
fi
exec "$MAVEN_HOME/bin/mvn" "$@"
