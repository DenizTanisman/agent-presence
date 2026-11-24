#!/bin/zsh
# Builds AgentPresence.app (host + `presence` CLI inside it), installs it to /Applications and links the CLI to
# ~/.local/bin/presence. The app starts on demand: the first `presence` call opens it.
set -euo pipefail
cd "${0:A:h}/.."
swift build -c release --arch arm64
BIN=.build/arm64-apple-macosx/release
APP="dist/AgentPresence.app"
rm -rf dist; mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/AgentPresence" "$APP/Contents/MacOS/AgentPresence"
cp "$BIN/presence" "$APP/Contents/MacOS/presence"
VERSION=$(cat VERSION 2>/dev/null || echo 0.1.0)
cat > "$APP/Contents/Info.plist" <<P
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>AgentPresence</string>
  <key>CFBundleDisplayName</key><string>Agent Presence</string>
  <key>CFBundleIdentifier</key><string>dev.deniz.agentpresence</string>
  <key>CFBundleExecutable</key><string>AgentPresence</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
P
# Ad-hoc signature by default; SIGN_IDENTITY="<name>" signs with one of your own code-signing identities.
codesign --force --deep --sign "${SIGN_IDENTITY:--}" "$APP"

pkill -x AgentPresence 2>/dev/null || true
sleep 0.3
rm -rf "/Applications/AgentPresence.app"
ditto "$APP" "/Applications/AgentPresence.app"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "/Applications/AgentPresence.app"
mkdir -p "$HOME/.local/bin"
ln -sf "/Applications/AgentPresence.app/Contents/MacOS/presence" "$HOME/.local/bin/presence"
echo "Installed: /Applications/AgentPresence.app, CLI: ~/.local/bin/presence"
