# Agent Presence

While an AI agent drives your Mac or your Android phone, you always know **that** it is driving, **what** it is
doing, **which step** it is on, **what you must and must not do**, and **when it is over**.

Two libraries with one look and one event format ([SPEC.md](SPEC.md)):

- [`mac/`](mac): Swift package. The island grows out of the MacBook notch. Comes with a host app and the `presence`
  CLI that agents call.
- [`android/`](android): Android library and host app. A One UI–style card at the top of the screen plus an
  ongoing / Live Update notification. Events arrive in-app or over adb from the Mac CLI.

```sh
presence start "Kaydedilenleri topluyor" --on phone --app Instagram --steps "Aç [ekran]|Kaydedilenler|Bağlantıları al [ekran]"
presence step
presence done "42 bağlantı alındı"
```

For Claude Code: `integrations/install-claude-code.sh` turns it on for every session
([details](integrations/claude-code.md)).

The single circle tracks the whole task. It orbits while a step runs, draws a check and moves to the next number
when the step ends, ripples once when everything is done, pulses amber when it needs you, and shakes red when it
failed. Opening it shows the tasks one under another with their state. Directives ("Klavye ve fareye dokunma") show
only in the closed size, as their own capsule, while a task that drives that input is still ahead. The owner can press **Durdur** at any time,
and the agent's next `presence` call then exits with code 3.
