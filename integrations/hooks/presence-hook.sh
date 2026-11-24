#!/bin/zsh
# Claude Code hook for Agent Presence: nothing that drives your screen is ever silent.
#   pre  (PreToolUse): a computer-use tool, or a Bash command that drives the phone over adb (input / uiautomator /
#        monkey / am start), opens "Claude is using your Mac/phone" with its "don't touch" capsule; later calls only
#        refresh what it is doing. If the agent announced its own session for that device, this stays out of the way.
#        If the owner pressed Durdur, the tool call is blocked and Claude is told why.
#   stop (Stop): the turn is over: every session of this Claude session that still runs is marked done.
# Installed by ../install-claude-code.sh. Never fails the tool call for its own errors.
set -u
P=${PRESENCE:-$(command -v presence 2>/dev/null || echo "$HOME/.local/bin/presence")}
[[ -x $P ]] || exit 0
input=$(cat)
sid=$(print -r -- "$input" | jq -r '.session_id // empty' 2>/dev/null)
[[ -n $sid ]] || exit 0

case "${1:-}" in
  pre)
    tool=$(print -r -- "$input" | jq -r '.tool_name // empty' 2>/dev/null)
    device="" what="$tool"
    if [[ $tool == *computer* ]]; then
      # Asking for access or listing apps does not touch anything.
      [[ $tool == *request_access* || $tool == *resolve_access* || $tool == *list_* ]] && exit 0
      device=mac
    elif [[ $tool == Bash ]]; then
      [[ $input == *adb* ]] || exit 0
      cmd=$(print -r -- "$input" | jq -r '.tool_input.command // empty' 2>/dev/null)
      if [[ $cmd =~ 'adb[^;&|]*shell[^;&|]*(input|uiautomator|monkey|am start)' ]]; then
        device=phone
        if [[ $cmd =~ 'input +([a-z]+)' ]]; then what=$match[1]; else what=start; fi
      fi
    fi
    [[ -n $device ]] || exit 0
    "$P" auto $device --tool "$what" --owner "$sid" >/dev/null 2>&1
    if [[ $? -eq 3 ]]; then
      echo "The owner pressed Durdur on the Agent Presence island: stop driving the device now and tell them where you stopped." >&2
      exit 2
    fi
    ;;
  stop)
    "$P" finish --owner "$sid" >/dev/null 2>&1
    ;;
esac
exit 0
