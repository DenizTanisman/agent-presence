#!/bin/zsh
# Makes Claude Code use Agent Presence by itself (see claude-code.md):
#   1. copies hooks/presence-hook.sh to ~/.local/share/agent-presence/
#   2. adds PreToolUse (computer-use tools, Bash) and Stop hooks to ~/.claude/settings.json
#   3. adds the "Agent Presence" instructions to ~/.claude/CLAUDE.md
# Run it again any time (it replaces its own entries). --uninstall takes all three back out.
set -euo pipefail
here=${0:A:h}
dest="$HOME/.local/share/agent-presence"
hook="$dest/presence-hook.sh"
settings="$HOME/.claude/settings.json"
claude_md="$HOME/.claude/CLAUDE.md"
mark_start="<!-- agent-presence:start -->"
mark_end="<!-- agent-presence:end -->"
mkdir -p "$dest" "$HOME/.claude"
[[ -f $settings ]] || echo '{}' > "$settings"
cp "$settings" "$settings.bak-agent-presence"

# Settings: drop our old entries (any hook whose command is ours), then add the current ones unless uninstalling.
tmp=$(mktemp)
jq --arg hook "$hook" --arg mode "${1:-install}" '
  def ours: (.hooks // []) | any(.command | tostring | contains("presence-hook.sh"));
  def clean(ev): if .hooks[ev] then .hooks[ev] |= map(select(ours | not)) | (if .hooks[ev] == [] then del(.hooks[ev]) else . end) else . end;
  .hooks = (.hooks // {}) | clean("PreToolUse") | clean("Stop")
  | if $mode == "--uninstall" then (if .hooks == {} then del(.hooks) else . end)
    else
      .hooks.PreToolUse = ((.hooks.PreToolUse // []) + [
        {"matcher": "mcp__computer-use__.*|mcp__remote-devices__computer.*", "hooks": [{"type": "command", "command": ($hook + " pre"), "timeout": 15}]},
        {"matcher": "Bash", "hooks": [{"type": "command", "command": ($hook + " pre"), "timeout": 15}]}
      ])
      | .hooks.Stop = ((.hooks.Stop // []) + [{"hooks": [{"type": "command", "command": ($hook + " stop"), "timeout": 20}]}])
    end
' "$settings" > "$tmp"
mv "$tmp" "$settings"

# CLAUDE.md: remove our block, then append the current one unless uninstalling.
touch "$claude_md"
awk -v s="$mark_start" -v e="$mark_end" '$0==s{skip=1;next} $0==e{skip=0;next} !skip' "$claude_md" > "$claude_md.tmp"
mv "$claude_md.tmp" "$claude_md"

if [[ ${1:-} == --uninstall ]]; then
  rm -f "$hook"
  echo "Agent Presence removed from Claude Code (settings backup: $settings.bak-agent-presence)."
  exit 0
fi

cp "$here/hooks/presence-hook.sh" "$hook"
chmod +x "$hook"
cat >> "$claude_md" <<EOF

$mark_start
## Agent Presence (the island on the Mac notch / the card on the phone)
Before you drive the Mac screen (computer use: clicks, typing, keys) or the phone (\`adb shell input\`, uiautomator,
launching apps), announce the task with \`presence\`, in the owner's language:
- \`presence start "<what you are doing>" --app <App> --steps "<task 1 [klavye,fare]|task 2|task 3 [ekran]>"\`
  (add \`--on phone\` when you drive the phone). 2–6 short tasks. Tag a task with what YOU drive during it:
  \`[klavye]\`, \`[fare]\`, \`[klavye,fare]\` on the Mac, \`[ekran]\` on the phone. Then the owner sees "don't touch"
  until those tasks are done. Untagged tasks only show progress.
- \`presence step\` when a task is done. \`presence intervene\` when you touch the device again after a pause.
- \`presence wait "<what you need from the owner>"\` when you need them to act, then \`presence resume\`.
- Finish with \`presence done "<result>"\` or \`presence fail "<why>"\`.
- Exit code 3 from any \`presence\` call means the owner pressed Durdur: stop at once, then \`presence fail "Durduruldu"\`.
A hook also opens a generic "Claude is using your Mac/phone" session when you forget, and marks your sessions done
when your turn ends.
$mark_end
EOF
echo "Agent Presence is on for Claude Code: hook $hook, settings $settings, instructions $claude_md."
