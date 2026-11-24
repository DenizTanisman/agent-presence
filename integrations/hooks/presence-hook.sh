#!/bin/zsh
# Claude Code hook for Agent Presence (see ../claude-code.md). Never fails the tool call for its own errors.
#   pre        PreToolUse (computer-use tools, Bash): a tool that drives the screen, or an adb command that drives
#              the phone, opens "Claude is using your Mac/phone" with its "don't touch"; later calls only refresh it.
#              If the owner pressed Durdur, the tool call is blocked and Claude is told why.
#   question   PreToolUse (AskUserQuestion): the question goes on the Mac island. One single-choice question can be
#              answered right there (the answer goes straight to Claude); "Aç" opens the session where it is asked.
#   permission PermissionRequest: "İzin ver / Reddet / Aç" on the island; a pick decides, "Aç" or no pick within 45 s
#              leaves it to the normal dialog.
#   notify     Notification (a prompt waiting, a dialog, input needed): a reminder that opens the session on tap.
#   answered   PostToolUse / UserPromptSubmit: this session's questions are answered: take them off the island.
#   stop       Stop: the turn is over: this session's running tasks are done, its questions are gone.
set -u
P=${PRESENCE:-$(command -v presence 2>/dev/null || echo "$HOME/.local/bin/presence")}
[[ -x $P ]] || exit 0
input=$(cat)
sid=$(print -r -- "$input" | jq -r '.session_id // empty' 2>/dev/null)
[[ -n $sid ]] || exit 0
REG="$HOME/Library/Application Support/AgentPresence/cli.json"

# Where the owner answers: the session's own tab in VS Code, or the terminal.
place() {
  case "${CLAUDE_CODE_ENTRYPOINT:-}" in
    *vscode*) print -r -- "vscode://anthropic.claude-code/open?session=$sid" ;;
    cli) print -r -- "com.apple.Terminal" ;;
    *) print -r -- "vscode://anthropic.claude-code/open?session=$sid" ;;
  esac
}
app() { [[ "${CLAUDE_CODE_ENTRYPOINT:-}" == cli ]] && print Terminal || print "VS Code"; }
project=$(print -r -- "$input" | jq -r '.cwd // empty' 2>/dev/null); project=${project:t}

case "${1:-}" in
  pre)
    tool=$(print -r -- "$input" | jq -r '.tool_name // empty' 2>/dev/null)
    device="" what="$tool"
    if [[ $tool == *computer* ]]; then
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

  question)
    # One single-choice question with up to 4 options: answerable on the island. Otherwise just a reminder.
    spec=$(print -r -- "$input" | /usr/bin/python3 -c '
import json,sys
d=json.load(sys.stdin); qs=(d.get("tool_input") or {}).get("questions") or []
if len(qs)==1 and not qs[0].get("multiSelect") and 1<=len(qs[0].get("options") or [])<=4:
    q=qs[0]; print(json.dumps({"q":q["question"],"labels":[o["label"] for o in q["options"]]}))
else:
    print(json.dumps({"q":(qs[0]["question"] if qs else "Claude bir soru soruyor"),"labels":[]}))' 2>/dev/null)
    [[ -n $spec ]] || exit 0
    q=$(print -r -- "$spec" | jq -r .q)
    labels=("${(@f)$(print -r -- "$spec" | jq -r '.labels[]')}")
    [[ ${#labels} -eq 1 && -z ${labels[1]} ]] && labels=()
    if (( ${#labels} > 0 )); then
      args=()
      for i in {1..${#labels}}; do args+=(--choice "o$i=${labels[$i]}"); done
      pick=$("$P" ask "$q" --owner "$sid" --title "Claude soruyor · $project" --app "$(app)" --open "$(place)" \
        "${args[@]}" --choice "open=Oturumda aç" --primary o1 --timeout 30 2>/dev/null)
      if [[ $pick == o<-> ]]; then
        label=${labels[${pick#o}]}
        print -r -- "$input" | /usr/bin/python3 -c '
import json,sys
d=json.load(sys.stdin); ti=d.get("tool_input") or {}
q=ti["questions"][0]["question"]
out={"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow",
     "permissionDecisionReason":"Answered on the Agent Presence island",
     "updatedInput":{**ti,"answers":{q:sys.argv[1]}}}}
print(json.dumps(out,ensure_ascii=False))' "$label"
        exit 0
      fi
      [[ $pick == open ]] && exit 0
    fi
    # Asked in the session itself: keep a reminder that takes the owner there.
    "$P" ask "$q" --owner "$sid" --title "Claude cevabını bekliyor · $project" --app "$(app)" --open "$(place)" --no-wait >/dev/null 2>&1
    ;;

  permission)
    what=$(print -r -- "$input" | /usr/bin/python3 -c '
import json,sys
d=json.load(sys.stdin); t=d.get("tool_name",""); i=d.get("tool_input") or {}
s=i.get("command") or i.get("file_path") or i.get("url") or i.get("description") or ""
s=" ".join(str(s).split())
print((t+": "+s)[:160] if s else t)' 2>/dev/null)
    pick=$("$P" ask "$what" --owner "$sid" --title "Claude izin istiyor · $project" --app "$(app)" --open "$(place)" \
      --choice "allow=İzin ver" --choice "deny=Reddet" --choice "open=Oturumda aç" --primary allow --destructive deny \
      --timeout 45 2>/dev/null)
    case $pick in
      allow) print -r -- '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}' ;;
      deny) print -r -- '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"The owner declined this on the Agent Presence island."}}}' ;;
    esac
    ;;

  notify)
    msg=$(print -r -- "$input" | jq -r '.message // "Claude seni bekliyor"' 2>/dev/null)
    "$P" ask "$msg" --owner "$sid" --title "Claude seni bekliyor · $project" --app "$(app)" --open "$(place)" --no-wait >/dev/null 2>&1
    ;;

  answered)
    [[ -f $REG ]] && grep -q "ask-${sid:0:8}" "$REG" 2>/dev/null || exit 0
    "$P" clear-asks --owner "$sid" >/dev/null 2>&1
    ;;

  stop)
    "$P" finish --owner "$sid" >/dev/null 2>&1
    "$P" clear-asks --owner "$sid" >/dev/null 2>&1
    ;;
esac
exit 0
