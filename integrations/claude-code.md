# Using Agent Presence from Claude Code

```sh
integrations/install-claude-code.sh              # turn it on (run again after updating; it replaces its own entries)
integrations/install-claude-code.sh --uninstall  # take it back out
```

It sets up two layers that work together.

## 1. Instructions: real tasks and directives

A block between `<!-- agent-presence:start -->` and `<!-- agent-presence:end -->` in `~/.claude/CLAUDE.md` tells Claude
to announce what it is about to do with `presence start "<what>" --steps "a [klavye,fare]|b|c"`, mark tasks with
`presence step`, ask with `presence wait`, and finish with `presence done` / `presence fail`. Tags on a task say what
Claude itself drives during it; while any such task is still ahead, the closed island shows "don't touch".

## 2. Hooks: nothing is ever silent

`~/.local/share/agent-presence/presence-hook.sh`, registered in `~/.claude/settings.json`:

| hook | when | what |
|---|---|---|
| PreToolUse `mcp__computer-use__.*`, `mcp__remote-devices__computer.*` | a computer-use tool is about to run | opens "Claude · Mac — Mac'i kullanıyor" with "Klavye ve fareye dokunma" (once per Claude session; later calls only update "tıklıyor / yazıyor / bakıyor") |
| PreToolUse `Bash` | the command drives the phone over adb (`shell input`, `uiautomator`, `monkey`, `am start`) | the same for the phone, with "Ekrana dokunma" |
| Stop | Claude's turn ends | every session of this Claude session that still runs is marked done ("Bitti") |

- When Claude announced its own session for that device (layer 1), the hook stays out of the way.
- When the owner pressed **Durdur**, the hook blocks the next screen-driving tool call and tells Claude to stop.
- Sessions are tied to the Claude session (`session_id` / `$CLAUDE_CODE_SESSION_ID`), so several Claude sessions
  side by side each get their own and never close each other's.
- Phone work shows on the phone and, as progress only, on the Mac (the owner sits there). Mac work shows only on the
  Mac. A "don't touch" appears only on the device being driven. The hook needs `jq`; macOS ships it.
