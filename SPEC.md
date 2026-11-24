# Agent Presence: shared spec (v1.1)

Two separate libraries, one look and one protocol:

- `mac/`: Swift package. `AgentPresenceKit` (UI library), `AgentPresence.app` (host that draws it), `presence` (CLI).
- `android/`: Gradle project. `:agentpresence` (Android library), `:host` (app "Agent Presence" that draws it and takes events over adb).

What it is for: while an AI agent (Claude) is operating a device, the owner always knows **that** it is, **what** it is doing,
**which step** it is on, **what they must and must not do** (directives), and **when it is over**. It never leaves them wondering
whether it is still running.

---

## 1. Event protocol (JSON, UTF-8)

Every change is one event. Unknown fields are ignored; missing optional fields keep the previous value.

```json
{
  "v": 1,
  "id": "claude-1732451234",
  "type": "start",
  "agent": "Claude",
  "app": "Spotify",
  "device": "mac",
  "title": "Çalma listesine şarkı ekliyor",
  "steps": [
    "Spotify'ı aç",
    {"title": "Listeyi bul", "uses": ["mouse"]},
    {"title": "Şarkıları ekle", "uses": ["keyboard", "mouse"]},
    {"title": "Kontrol et", "dont": ["Spotify'ı kapatma"]}
  ],
  "step": 1,
  "detail": "3/10",
  "dont": [],
  "do": [],
  "message": "Ödeme ekranında onayın gerekiyor",
  "ts": 1732451234567
}
```

| field | meaning |
|---|---|
| `id` | session id (required). One session = one task the agent does on a device. |
| `type` | see table below (required) |
| `agent` | who acts, default `"Claude"` |
| `app` | the app/area being driven ("Spotify", "Ayarlar") |
| `device` | `"mac"` or `"phone"`: which device is being driven. A session shown on the *other* device is a **mirror** and carries a small device badge. |
| `title` | one line: what the agent is doing |
| `steps` | the plan (optional; without steps the circle shows a spinner, no number). Each entry is a short label string, or an object `{"title", "uses", "dont"}`: `uses` ⊂ `keyboard`, `mouse`, `touch` (the phone screen) says the agent itself drives that input during this step; `dont` adds step-only "don't"s. |
| `step` | 1-based current step |
| `detail` | the one critical datum for the compact right side ("3/10", "12 sn", "%40") |
| `dont` | things the owner must NOT do during the whole task (replaces the list; `[]` clears). Usually empty: per-step `uses` generate the common ones. |
| `do` | things the owner should do (replaces the list; `[]` clears). Not for "I'll tell you when it's done": the island always does. |
| `message` | text for attention / done / fail |
| `ttl` | seconds of silence after which the host itself finishes the running session (see "Never left blocked") |
| `for` | seconds a `hide` lasts before the warnings come back by themselves (default 30) |

| `type` | effect |
|---|---|
| `start` | create or replace the session. phase = running, step = `step` or 1. **Announce** (expanded). |
| `intervene` | "I am about to act on your device now". phase = running, applies any given fields. **Announce**. Agents send this every time they start touching the device again after a pause. |
| `step` | steps before `step` become done, `step` becomes active (no `step` field = next). Updates title/detail if given. **Flash** (compact). |
| `detail` | update detail (and title if given). No mode change. |
| `directive` | replace `dont` and/or `do`. If the new lists are non-empty and differ: **Announce**. |
| `attention` | phase = waiting: the agent needs the owner (`message`). Its `dont` list is set aside (not shown, kept) until `resume`/`step`/`intervene`: the owner has to act now. **Sticky expanded** until the owner taps it or a later `resume`/`step`/`intervene`. Sound/vibration. |
| `resume` | phase back to running. |
| `done` | every step done, phase = done, `dont` cleared, `do` cleared unless this event carries its own `do`. **Announce**, then the done state **stays** (minimal on Mac, compact on Android) until the owner taps it away or a new session starts. Sound/vibration. |
| `fail` | phase = failed, `dont` cleared, `do` cleared unless given, `message` = why. Same lifecycle as done, red. |
| `end` | remove the session now, no animation beyond fade. |
| `expand` / `collapse` | UI control (tests, CLI). |
| `hide` | the agent must see or tap what is under the island/card: on the **driven device only**, every written part (card, pill, capsules, circles) is taken off the screen and the **screen edges glow red** instead (Samsung edge-lighting style), so the owner still knows not to touch. Lasts until `unhide`, or `for` seconds (default 30). Only the agent can do this: there is no button for it. |
| `unhide` | the written warnings come back at once; the edge glow stops. `done`, `fail`, `attention` and `end` also unhide. |

### Which device shows a session

A session driven on the **phone** shows on the phone and is mirrored on the Mac (the owner sits at the Mac while the
agent works on the phone). A session driven on the **Mac** shows only on the Mac: the phone never shows Mac work.

### Directives on screen

The **active directives** of a running session are computed, never typed twice:

1. Union of `uses` over every step that is not finished yet (the current one and the ones after it). If even one
   remaining step drives the keyboard or the mouse, the warning shows. Generated text (the island lives on a
   MacBook, so the pointer is the trackpad): keyboard + mouse → "Klavyeye ve trackpad'e dokunma",
   keyboard → "Klavyeye dokunma", mouse → "Trackpad'e dokunma", touch → "Ekrana dokunma".
2. Plus the `dont` of those remaining steps, plus the session `dont`, **except** a `dont` that only repeats a
   generated one: when a keyboard/mouse warning is generated, a `dont` mentioning klavye/keyboard, fare/mouse,
   trackpad or imleç is dropped; when the touch warning is generated, one mentioning ekran/screen/telefon/phone is
   dropped. One warning is said once.
3. Plus the session `do` (shown green).

Directives belong to the device being driven (`device`). A mirror on the other device shows the session's progress
(circle, task list) but **never its directives**: a phone task never tells the owner to keep off the Mac, and a Mac task
never puts "don't touch" on the phone.

Nothing is active while the session waits for the owner (`attention`: they must act now) or once it is done/failed.
Directives appear **only in the closed sizes** (compact capsule). The expanded view never shows them: it is the task list.
When a session has no active directive, the idle island is just the minimal circle: it says "something is running" and
its state, nothing more.

### Choices and opening the place

An `attention` may carry `open` (what tapping it opens: any URL such as `vscode://anthropic.claude-code/open?session=…`,
a path, or an app bundle id) and `choices` (`[{"id","label","style"}]`, style `primary` / `destructive`). The card
shows the choices as buttons (two short ones side by side, otherwise a list); the pick is written to the state as
`choice` for the agent waiting on it (`presence ask` prints it). A choice with id `open` also opens `open`. Without
choices, a tap on the waiting island opens `open` directly. `choose` (field `choice`) records a pick made elsewhere.

### Edge glow while hidden

While a session on this device is hidden: a full-screen, touch-through layer draws a red glow along all four screen
edges, following the display's rounded corners (Android: `Display.getRoundedCorner`; Mac: the screen frame),
about 10 pt wide, `#FF3B30` fading inward, breathing 55 % ↔ 100 % over 1.6 s. Nothing else of the island shows, so
taps and screenshots by the agent reach the app below. Mirrors on the other device are not affected.

### Never left blocked

The end must reach the owner even when the agent goes quiet or the link to the phone drops (USB unplugged, Wi-Fi adb
gone). A running session with a `ttl` that gets no event for `ttl` seconds is finished **by the host itself**: phase
done, message "Bitti, artık kullanabilirsin" ("Done, you can use it now"), the usual done announcement (expanded 5 s,
green glow, sound/vibration) and then the green check. A waiting session never expires (the owner must act). Any
later event for that id simply continues it (a `step` makes it run again, with the warning card first if it has a
directive). The CLI sends `ttl` 120 on `start`/`intervene` and 30 for the hook's automatic sessions; agents should
still send `done` the moment they stop touching the device, before anything else. The CLI retries a failed phone
delivery (3 tries, then the other adb link: USB ↔ Wi-Fi).

### State the host reports back

After each event the host returns (Android: broadcast result data, base64 JSON; Mac: `~/Library/Application Support/AgentPresence/state.json`):

```json
{"v":1,"sessions":[{"id":"claude-1","phase":"running","step":2,"total":4,"stopRequested":false,"acknowledged":false,"updated":1732451234567}]}
```

`stopRequested` becomes true when the owner taps **Durdur**. The agent must check it (the CLI exits with code 3) and stop.
`phase` values: `running`, `waiting`, `done`, `failed`.

---

## 2. Display modes

Three sizes of the same thing (Dynamic-Island vocabulary), plus a warning card that always comes first:

- **Warning first** (owner's rule: tell the owner what to do / not do before telling them about the process, or they
  may already have done what they must not). Whenever a session on this device gets a directive it did not show a
  moment ago (start, intervene, a new `dont`/`do`, or `resume` bringing a set-aside "don't" back), the island first
  opens as a **warning card** for **3 s**: header `app icon · agent · app` and, on the right, the red word
  "Dokunma"; then each directive on its own line in large type (17 semibold) with its 32 pt gradient disc (red hand
  for `dont`, green check for `do`); under them, secondary, the session title. Red glow. Only after that comes the
  expanded task list (5 s, for start/intervene) or the resting size (for the other cases). Without a directive the
  task list opens directly, as before. The warning card is click-through while the agent drives the input.

- **Expanded**: header (`agent · app`, `Adım x/n` or status), title, then **the tasks one under another**: each row is a
  small status circle (done ✓ green / active orbit blue / pending grey number / waiting amber ! / failed red ✕), the task
  title, and its status word on the right (Bitti / Sürüyor / Sırada / Seni bekliyor / Olmadı). The `attention` or `fail`
  message sits under the row it belongs to; the `done` message under the title. Then buttons **Durdur** and **Küçült**
  (done/failed: **Kapat**). **No directives, no horizontal stepper** here. Without steps the list is one row: the title.
- **Compact**: one line: left = the step circle + app, right = the critical datum (`detail`, else `step/total`). The active
  directives sit in a **separate capsule under the pill** (a gap between them, dark red; `do` items green). A directive
  never leaves the screen while it is active.
- **Minimal**: just the step circle. Mac: right of the notch, inside the menu bar. Android: right under the camera hole,
  centred on it, just below the status bar (owner's choice: beside the hole is not possible because on One UI an app
  overlay inside the status-bar band cannot be tapped, the status bar swallows the touch).
  `AgentPresence.minimalInStatusBar = true` puts it in the band right of the hole instead (looks like the Mac, but
  then only the notification opens the task list). With two live sessions the second one falls to its own small circle next to the first.

Which session gets the big island (the others are small circles): an unseen `attention` first, else a live session
with an active directive on this device (it must stay readable), else the newest live one, else the newest. An announcement
may show another session for its 5 s; at rest the rule above wins again.

Mode rules (both platforms):

| situation | Mac | Android |
|---|---|---|
| start / intervene with a directive on this device | **Warning card 3 s**, then Expanded 5 s | same |
| a new directive (directive event, or resume bringing one back) | Warning card 3 s, then rest | same |
| start / intervene without directive, done / fail | Expanded for **5 s** (macOS banner time) | Expanded for 5 s |
| step change | Compact for 3 s | Compact for 3 s |
| idle while running, no active directive | **Minimal** | **Minimal** (under the camera), stays until the task ends |
| idle while running, directive active | Compact + directive capsule (never smaller) | Compact + directive capsule |
| attention (waiting) | Expanded, sticky | Expanded, sticky |
| done / failed after the 5 s | Minimal (green check / red x), stays until tapped | Minimal (green check / red x), stays until tapped |
| tap on minimal or compact | Expanded (until tapped again or 8 s without hover) | Expanded (until tapped again or 8 s) |
| owner taps Kapat on done/failed | session removed | session removed |

---

## 3. The step circle (the heart of it)

One circle tracks the whole process instead of a long horizontal row. Base diameter 28 (pt/dp), smaller in minimal.

| state | look | animation |
|---|---|---|
| pending (stepper only) | fill `#2C2C2E`, number `#8E8E93` | none |
| active | fill `#16233F`, 2 px ring `#3B82F6`, number `#93C5FD` | a 90° arc in `#60A5FA` orbits **outside** the ring, 1.1 s per turn (**devam ediyor**) |
| no steps, running | same as active without a number: small dot in the middle | same orbit |
| step finished | fill turns `#34C759`, check mark **draws itself** (0.35 s), pop 1 → 1.12 → 1 | then after 0.5 s the next number slides/scales in, back to active |
| all done | green fill + check | check draws, then one **ripple** ring expands to 1.8× and fades (0.8 s) (**bitti**) |
| waiting (attention) | fill `#F5A524`, `!` in black | halo pulses (scale 1 → 1.5, alpha .6 → 0, 1.4 s, repeating) |
| failed | fill `#FF453A`, `✕` in white | one short horizontal shake |

Mirror badge: a 12 px circle at the bottom-right of the big circle with a laptop or phone glyph.

## 4. Look (v1.2, "premium")

No emoji anywhere: Mac uses SF Symbols, Android uses vector icons (Material Symbols Rounded paths). Same shapes and
colors on both.

- **Surfaces.** Mac island pure black `#000000` (merges with the notch). Android card `#0E0E10`, 1 px hairline
  `#FFFFFF14`, radius 28 dp, soft shadow. Inner panels (the task list) `#FFFFFF0A`, radius 16.
- **Type.** Mac: SF Pro; numbers and step digits SF Pro Rounded. Android: system font (One UI Sans). Title 15
  semibold, tracking −0.2; secondary 13 regular `#9A9AA2`; small labels 11.5 semibold. White `#F5F5F7`, never pure
  `#FFFFFF` text on black.
- **Accents.** Running `#3B82F6` (text `#7AB8FF`), done `#30D158` (text `#4ADE80`), waiting `#FF9F0A`
  (text `#FFB340`), failed `#FF453A` (text `#FF6961`), pending `#8E8E93`.
- **App identity.** When the target app is found on the device (Mac: in /Applications, /System/Applications; Android:
  a launcher app whose label matches `app`), its real icon is shown: 18 pt beside the circle in compact, 22 pt in
  the expanded header. Otherwise a neutral glyph (Mac `app.fill`, Android a rounded square) in `#2C2C2E`.
- **Directive capsule** (closed sizes only). Height 36, radius = height/2, background `#141416`, 1 px border in the
  accent at 40 % (`dont` red, `do` green), a soft glow of the accent at 25 % (radius 10). Leading 22 pt circle filled
  with the accent gradient (`#FF5A4F → #E5332A` for `dont`, `#34D399 → #16A34A` for `do`) holding a white 12 pt
  icon (hand raised / checkmark). Text `#F5F5F7` 13.5 semibold. Several directives: one capsule each, stacked
  (gap 5), like the lines of the warning card.
- **Status badges** in the task list: capsule, 11.5 semibold, padding 8×3, fill = accent at 16 %, text = accent
  text color. Words: Bitti / Sürüyor / Sırada / Seni bekliyor / Olmadı.
- **Task rows.** 20 pt status circle, title 13.5 (active row semibold `#F5F5F7`, done `#C7C7CC`, pending `#8E8E93`),
  badge on the right. The active row has a left-to-right gradient of its accent at 14 % → 0, radius 12.
- **Buttons.** Capsules, height 30, 12.5 semibold, leading SF Symbol / vector icon: **Durdur** (`stop.fill`) red at
  18 % fill with red text; **Küçült** (`chevron.up`) and **Kapat** (`xmark`) white at 10 % with `#F5F5F7` text;
  **Tamam** (`checkmark`) amber fill with black text.
- **Glow.** Every alert glows in its accent, like the directive capsule: the island/card while it waits for the owner
  (amber, softly pulsing 1.6 s), when it failed (red) and for the done announcement (green); the minimal circle too.
  Glow = shadow of the shape in the accent, 45 % opacity, radius 14 (pulse 30 % ↔ 60 %). Nothing glows while it
  simply runs.
- **Motion.** Springs (response 0.4, damping 0.82); capsules enter with scale 0.92 → 1 and fade.

## 5. UI strings (TR default, EN when the system language is not Turkish)

| key | tr | en |
|---|---|---|
| stop | Durdur | Stop |
| stopping | Durdurma istendi | Stop requested |
| minimize | Küçült | Minimize |
| close | Kapat | Close |
| ok | Tamam | OK |
| done | Bitti | Done |
| failed | Olmadı | Failed |
| waiting | Seni bekliyor | Needs you |
| step_of | Adım %d/%d | Step %d of %d |
| hands_off | Dokunma | Hands off |
| on_mac | Mac'te | On Mac |
| on_phone | Telefonda | On phone |
| running | Sürüyor | Running |
| pending | Sırada | Next |
| no_keys_mouse | Klavyeye ve trackpad'e dokunma | Don't touch the keyboard or trackpad |
| no_keys | Klavyeye dokunma | Don't type |
| no_mouse | Trackpad'e dokunma | Don't touch the trackpad |
| no_touch | Ekrana dokunma | Don't touch the screen |

## 6. Android transport (adb, used by the Mac CLI)

- Receiver: `dev.deniz.agentpresence.host/.EventReceiver`, action `dev.deniz.agentpresence.EVENT`,
  `android:permission="android.permission.DUMP"` (only adb shell holds it, so other phone apps cannot fake events).
- Payload: string extra `b64` = base64 (NO_WRAP) of the event JSON. Several events may be sent at once as a JSON array.
- Result: `setResultData(base64(stateJson))`; `adb shell am broadcast ...` prints `Broadcast completed: result=0, data="<b64>"`.
- In-app use (an app embedding the library): `AgentPresence.send(context, event)` / `AgentPresence.state()`.

## 7. Mac transport

- CLI → app: `DistributedNotificationCenter`, name `dev.deniz.agentpresence.event`, `object` = event JSON string.
- The app writes `~/Library/Application Support/AgentPresence/state.json` after each event and `ready` (pid) at launch.
- The CLI starts the app (`open -g -b dev.deniz.agentpresence`) when it is not running and waits for `ready`.
