#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"

echo "==> Compilando interfaz Angular..."
cd "$root/ui"
npm install
npm run build

static="$root/backend/src/main/resources/static"
rm -rf "$static"
mkdir -p "$static"
cp -r "$root/ui/dist/ui/browser/." "$static/"

echo "==> Empaquetando backend..."
cd "$root/backend"
mvn clean package -DskipTests

echo "Listo: backend/target/NubePrivadaCompartida.jar"
