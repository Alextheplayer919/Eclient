# Playtest — proxy mode, Minecraft 1.21.111 (protocol 844)

The proxy path needs **no patching**: stock Minecraft + the Eclient app. The app
runs a local relay the game connects to; the game talks to the real server through
it.

Everything below is about making a playtest **reportable**: if something breaks,
the log should already say which half broke.

---

## 1. Install

| | |
|---|---|
| Minecraft | **1.21.111, arm64** — the stock build the attach runtime is pinned to (sha256 `69f6584c…`). Do **not** use the patched APK for this test; proxy mode is the point. |
| Eclient | the signed APK from the `playtest-1.21.111` release (link in the release description). Same signature every build, so installing over an older playtest keeps your config. |
| Android | 8.0+ (minSdk 26) |

Grant when asked: overlay (the panel), storage/Downloads if offered (the log file),
notification (the relay runs as a foreground service).

> Debug builds use `applicationIdSuffix = .debug`, so they install **side by side**
> with the release build and do not share config. Use the release APK for play.

## 2. Before you press connect — confirm the target

The app logs a self-check at startup and again on every capture:

```
[TargetVersion] target=1.21.111(protocol 844) resolved=1.21.111~1.21.114(protocol 844) exact=true
[TargetVersion] definitions: exact=true chosen=v844 block=block_palette_v844.nbt item=runtime_item_statesv844.json known=1001,975,944,924,897,860,844,…
```

* `exact=true` → this build speaks 1.21.111. Good to play.
* `exact=false` + a `WARNING:` line → the vendored protocol library lost 844 and
  something older resolved. **Stop here and report** — packet layouts will differ
  from the game's, and the symptom would be confusing (mismatch/handshake errors)
  rather than obviously version-related.
* `definitions: exact=false` → the block palette / item-states files for 844 are
  missing from `assets/nbt/`. Same rule: report instead of playing.

Log file: **`Downloads/baba.txt`** (the app appends; the crash logger writes the
same file). That is what to send back.

## 3. Connect

Prerequisite: **a signed-in account** in the app — the relay authenticates you to
the real server itself, so it needs the account, not the game.

1. Open Eclient → set the target server (**host + port**) → start it. That is what
   starts the relay: it binds **127.0.0.1:19150** locally and advertises itself on
   the LAN as **`rubidium` / `RubidiumClient`**.
2. Watch for the negotiated line, which is the per-session truth:
   ```
   [AutoCodecListener] negotiated: client=844 -> codec 844 (1.21.111~1.21.114)
   ```
   `client=844` is your game telling the relay what it speaks; `codec 844` is what
   the relay chose. If those differ, that is the bug — screenshot it.
3. In Minecraft → **Play → Friends / LAN**, join the **`rubidium`** entry. Do *not*
   type the server address directly: that path skips the relay entirely and no
   module will do anything (and it will look like "everything is broken").
4. Expect `[ConnectionManager] state → CONNECTING → HANDSHAKING → PLAYING`.

## 4. What "working" looks like

| Check | Where |
|---|---|
| Relay is the one you joined | you joined the `rubidium` entry from Friends/LAN, not a typed address |
| Modules see the world | panel shows entity count / the module list reacts; ESP, ArrayList, ArmorHud are the cheapest visual confirms |
| Combat modules act | KillAura/CrystalAura-class modules fire on real players; the panel's own status lines update |
| No silent death | `baba.txt` shows no repeated exception spam; the overlay stays responsive |

## 5. What to report, and how

For **any** problem, capture:

1. `Downloads/baba.txt` (whole file).
2. The exact game version string (Settings → Profile, or the APK filename).
3. What you were doing, and the last thing that happened.
4. For a disconnect: whether it happened at join, mid-fight, or on teleport/dimension
   change — those are three different failure classes.

Useful markers to grep for in the log before sending:

```
TargetVersion          — did this build even speak 844
negotiated:            — did the codec actually match the game
AutoCodecListener      — handshake path
UnknownPacket          — a packet the vendored library could not decode (version drift signal)
ConnectionManager      — where in the session lifecycle it died
```

## 6. Known, expected, not bugs

* **`Schematica` wire path** is gated at protocol 975 (`DebugDrawerBoxes.MIN_PROTOCOL`).
  On 844 it uses the fallback path — intended, and it says so in the module status.
* **Other game versions** through the relay: the target only decides what the relay
  advertises *before* it sees a client; each client still negotiates its own codec
  and definitions. 1.21.130 or 1.26.x clients work; only 1.21.111 is the tested
  claim.
* **A missing `block_palette_v*.nbt`** for a *newer* protocol falls back to the next
  older palette. Harmless for 844 (its palette ships). Noted here because it would
  matter if you retarget.

## 7. If it is unplayable, the fallback is the patched build

Proxy mode and the in-game agent are independent paths to the same modules:

* **proxy** = stock MC + Eclient app (this document)
* **agent** = patched MC (`patched-r<NN>`, 324 MB) + Eclient app in memory mode

If the relay misbehaves on 1.21.111, the patched build is the other half of the
same project and does not need the relay at all. Both are pinned to the same game
version on purpose.
