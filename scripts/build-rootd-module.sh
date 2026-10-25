#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/dist}"
MODSRC="$ROOT/tools/rootd/module"
BIN="$ROOT/tools/rootd/lingchat-rootd"

if [ ! -x "$BIN" ]; then
  echo "missing compiled daemon: $BIN" >&2
  exit 1
fi

mkdir -p "$OUT"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp "$MODSRC/module.prop" "$MODSRC/service.sh" "$MODSRC/uninstall.sh" "$STAGE/"
mkdir -p "$STAGE/bin"
cp "$BIN" "$STAGE/bin/lingchat-rootd"
chmod 755 "$STAGE/service.sh" "$STAGE/uninstall.sh" "$STAGE/bin/lingchat-rootd"

ZIP="$OUT/LingChat-VolumeKey-Magisk.zip"
rm -f "$ZIP"
(
  cd "$STAGE"
  zip -r "$ZIP" . >/dev/null
)
echo "$ZIP"

