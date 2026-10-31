#!/bin/bash
# Removes the Nuvio Watch helper.
AGENT="$HOME/Library/LaunchAgents/app.nuviowatch.helper.plist"
launchctl bootout "gui/$(id -u)" "$AGENT" 2>/dev/null || true
rm -f "$AGENT"
rm -rf "$HOME/Library/Application Support/NuvioWatch"
echo "Nuvio Watch helper removed."
