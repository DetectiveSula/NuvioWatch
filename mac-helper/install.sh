#!/bin/bash
# Installs the Nuvio Watch helper so it starts at login.
# Usage: bash install.sh SETUP_CODE ["Device name"] [profile number]
set -euo pipefail

CODE="${1:-}"
NAME="${2:-$(scutil --get ComputerName 2>/dev/null || echo Mac)}"
PROFILE="${3:-}"
if [ -z "$CODE" ]; then
  echo "Usage: bash install.sh SETUP_CODE [\"Device name\"] [profile number]"
  echo "Find the setup code in Nuvio Watch → Settings → Mac (Nuvio Desktop)."
  exit 1
fi

# The setup code is the Nuvio account id, a UUID like 1a2b3c4d-1234-5678-9abc-def012345678.
if ! [[ "$CODE" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "\"$CODE\" isn't a setup code."
  echo "Copy the full command from Nuvio Watch → Settings → Mac (Nuvio Desktop) on your phone;"
  echo "the setup code is the long code with dashes in it."
  exit 1
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="$HOME/Library/Application Support/NuvioWatch"
AGENT="$HOME/Library/LaunchAgents/app.nuviowatch.helper.plist"
mkdir -p "$DIR" "$HOME/Library/LaunchAgents"

cp "$HERE/nuvio-watch-helper" "$DIR/nuvio-watch-helper"
chmod +x "$DIR/nuvio-watch-helper"
xattr -dr com.apple.quarantine "$DIR/nuvio-watch-helper" 2>/dev/null || true

# Keep the device id across reinstalls, so it stays the same device in the app.
DEVICE_ID="$(/usr/bin/plutil -extract deviceId raw "$DIR/config.json" 2>/dev/null || uuidgen)"
PROFILE_JSON="null"
if [ -n "$PROFILE" ]; then PROFILE_JSON="$PROFILE"; fi
cat > "$DIR/config.json" <<JSON
{"setupCode": "$CODE", "deviceId": "$DEVICE_ID", "deviceName": "$NAME", "profileIndex": $PROFILE_JSON}
JSON
chmod 600 "$DIR/config.json"

cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>app.nuviowatch.helper</string>
  <key>ProgramArguments</key><array><string>$DIR/nuvio-watch-helper</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>$DIR/helper.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)" "$AGENT" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT"

echo "Nuvio Watch helper installed as \"$NAME\"."
echo "Now open Nuvio Desktop → Settings and turn on Discord Rich Presence, then restart Nuvio Desktop."
echo "Log: $DIR/helper.log"
