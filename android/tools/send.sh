#!/bin/zsh
# Sends one Agent Presence event (or a JSON array of events) to the phone over adb and prints the state back.
#   tools/send.sh '{"v":1,"id":"t1","type":"start","app":"Ayarlar","steps":["Aç","Bul"],"dont":["Telefona dokunma"]}'
#   tools/send.sh            # state only
# Picks the device like adb does (ANDROID_SERIAL); exit code 1 when the host app rejected the event.
# -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES) reaches the app even before it was ever opened.
set -euo pipefail
ADB=${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}
extra=()
if (( $# > 0 )); then
  extra=(--es b64 "$(printf '%s' "$1" | base64 | tr -d '\n')")
fi
out=$("$ADB" shell am broadcast -n dev.deniz.agentpresence.host/.EventReceiver -a dev.deniz.agentpresence.EVENT -f 0x20 "${extra[@]}")
code=$(print -r -- "$out" | sed -n 's/.*result=\([0-9-]*\).*/\1/p')
data=$(print -r -- "$out" | sed -n 's/.*data="\([^"]*\)".*/\1/p')
if [[ -n $data ]]; then
  print -r -- "$data" | base64 -D
  echo
else
  print -r -- "$out" >&2
fi
[[ $code == 0 ]]
