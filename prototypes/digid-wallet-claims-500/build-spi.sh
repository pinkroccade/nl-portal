#!/usr/bin/env bash
# Bouwt de prototype-mapper tegen de jars van de Keycloak-image zelf (zelfde versie als de runtime).
set -euo pipefail
cd "$(dirname "$0")"
IMAGE=quay.io/keycloak/keycloak:26.8.2
LIB=keycloak-spi/build/kc-lib
OUT=keycloak-spi/build/classes
mkdir -p "$LIB" "$OUT" keycloak-spi/build/providers
cid=$(docker create "$IMAGE")
docker cp "$cid:/opt/keycloak/lib/lib/main/." "$LIB" > /dev/null
docker rm "$cid" > /dev/null
mise exec -- javac --release 21 -proc:none -cp "$LIB/*" -d "$OUT" keycloak-spi/src/nl/nlportal/prototype/*.java
cp -r keycloak-spi/src/META-INF "$OUT/"
mise exec -- jar --create --file keycloak-spi/build/providers/nlportal-keycloak-spi.jar -C "$OUT" .
echo "gebouwd: keycloak-spi/build/providers/nlportal-keycloak-spi.jar"
