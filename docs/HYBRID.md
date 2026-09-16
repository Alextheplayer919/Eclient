# HYBRID — native eyes + packet brain (attach-experiment branch)

## Concept

One client, two halves:

- **Native side** (`attach/src/probe.cpp` → `libeclient_attach.so`) — loaded
  *into* the Minecraft process. It can see live game state that packets can't
  tell you, e.g. the real local-player transform even when silent rotations
  make the server think you're staring elsewhere. Thin producer only: it reads
  state, streams it out, never implements game logic.
- **Packet side** (the whole existing Kotlin relay) — unchanged brain. It owns
  networking, protocol, modules, rotations. Now it also *consumes* the native
  stream.

Combat/module logic stays 100 % Kotlin (user standing rule). The .so is eyes,
not hands.

## Phase A — native self-state (this spike)

```
┌─ MCPE process ─────────────────────────┐
│ libeclient_attach.so                   │
│  hook on Player::tick (or whatever the │
│  signature resolves to)                │
│  read StateVectorComponent:            │
│    pos @ +0x00, vel @ +0x18            │
│  read ActorRotationComponent           │
│  ──TCP 127.0.0.1:19137──┐              │
└─────────────────────────┼──────────────┘
                          ▼
┌─ Eclient app (Kotlin) ─────────────────┐
│ NativeFeedServer (daemon, loopback only)│
│  parse EA1 lines                       │
│  → EntityTracker.ingestNativeFrame()   │
│ EntityTracker.nativeActive (fresh<500ms)│
│  → suppresses stale AuthInput pos/rot  │
│  echoes so silent rotations stop lying │
│  to the modules                        │
└─────────────────────────────────────────┘
```

### Wire protocol (line-oriented, ~33 Hz max)

```
EA1 <xF> <yF> <zF> <rotA F> <rotB F> <vxF> <vyF> <vzF> <tickL>\n
```

- First thing on connect: `EA1` magic — anything else is dropped (loopback
  only, but cheap sanity).
- **rotA vs rotB is an ASSUMPTION** (rotA=pitch, rotB=yaw, matching
  MovePlayerPacket rotation.x=pitch rotation.y=yaw). Validate on-device against
  the 10 s logcat sanity line; fix order in `NativeFeedServer` parser only if
  swapped, never touch the .so for that.
- Velocity heuristic: `\|v\| ≥ 64 m/s` clamped to 0 (guards garbage reads while
  the StateVector layout assumption is unverified).
- Throttles: ≥30 ms between frames; reconnect ≥3 s apart; `MSG_NOSIGNAL` so a
  dead socket can't SIGPIPE the game.

### Failure semantics

Feed drops >500 ms → relay silently falls back to the old packet-echo self
tracking. Nothing else depends on the feed; a crash in the .so or an
uninstalled patch leaves the app exactly as before.

## On-device build/install loop (TERMUX, no PC)

Setup once:

```sh
pkg update && pkg install apktool apksigner openjdk-17 python
```

Each iteration:

1. Build on GitHub — `attach_build.yml` gives `libeclient_attach.so`,
   `build_diagnose.yml` gives the signed **Eclient APK** (this branch is
   whitelisted there).
2. On the phone: grab the stock Minecraft APK (`com.mojang.minecraftpe`; pull
   it out of `/data/app` with `termux-file-editor`/`adb`-free extractor app, or
   use your own installed copy via `apktool/pull-apk` apps — no root needed).
3. In Termux:
   ```sh
   cd attach/patcher
   ./patch-termux.sh /sdcard/Download/base.apk /sdcard/Download/libeclient_attach.so ec.apk
   ```
4. Install `ec.apk` from the Files app (signature differs from Mojang's —
   uninstall stock first; worlds stay in external storage, but back up anyway).
5. Launch patched MCPE, then open Eclient and connect a session.
6. Verify: DiagLog line `NativeFeed ... connected`, then sanity — X/Z change
   when you walk; with fly on and frozen clientside, native Y keeps reporting
   ground truth.

Differences from `patch.sh` (desktop): no `zipalign` (skipped; unaligned
installs fine), keystore at `$HOME/.eclient/`, tools via `pkg`.

## GO / NO-GO gate for Phase A

- Feed connects + self-position matches reality under fly (incl. cases where
  AuthInput lies) → **GO** → Phase B (world state: entity list streamed the
  same way).
- If the Termux patch loop fails, the socket proves unstable, or the tick
  signature blows up on the current MCPE version → native stays dead,
  `attach-experiment` returns to parked status, no loss to `main`.

## Risks / costs to watch

- **Per-version signature maintenance**: the offset pattern in `hook.ksig`
  must be re-derived per MCPE update (use the usual pattern-scan workflow;
  `And64InlineHook` is vendored already).
- **Keystore consistency across patch iterations** — keep
  `$HOME/.eclient/patch-debug.keystore` or you'll have to uninstall/reinstall
  the patched game every time.
- Detection surface: proxy approach is remote-undetectable in wire terms; a
  patched client is NOT. Keep this experiment off servers with client-integrity
  checks. That's a user decision, flagged once here.

## Phase B / C sketch (NOT in this spike)

- B: entity positions (same socket, `EA1E` frames) → real ESP-quality world
  state regardless of what the server sends.
- C: in-game overlay render (managed by the .so, config from Kotlin) → ESP
  without touching the proxy's console UI.
