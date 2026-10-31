#!/bin/bash
# One-line installer for the Nuvio Watch Mac helper:
#   curl -fsSL https://raw.githubusercontent.com/DetectiveSula/NuvioWatch/main/mac-helper/get.sh | bash -s SETUP_CODE ["Device name"] [profile number]
set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Missing setup code. Copy the full command from Nuvio Watch → Settings → Mac (Nuvio Desktop)."
  exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading the Nuvio Watch helper…"
curl -fsSL -o "$TMP/NuvioWatch-Mac.zip" "https://github.com/DetectiveSula/NuvioWatch/releases/latest/download/NuvioWatch-Mac.zip"
ditto -x -k "$TMP/NuvioWatch-Mac.zip" "$TMP"
bash "$TMP/NuvioWatch-Mac/install.sh" "$@"
