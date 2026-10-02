# Bridge — keeping the app and the in-game agent in sync

Research + decisions for the link between **Eclient (the app)** and **Eclient-Attach (the agent inside
Minecraft)**, written while building hybrid-lite (`docs/HYBRID_LITE.md`). Everything marked **verified**
was read in the native source or in current Android documentation; everything marked **not verified**
needs a device.

## 1. How the link works today (verified)

| | |
|---|---|
| Transport | TCP on `127.0.0.1:38170`, newline-delimited JSON, **the agent is the server**, the app is the client |
| Auth | a token on every line; wrong token ⇒ `bad or missing token` + hang-up. The token lives in a file in shared storage (`Download/eclient_agent.token`) |
| Agent → app | state push every 50 ms (~20 Hz) once the client has authenticated, plus acks |
| Concurrency | **one client at a time**: `listen(fd, 1)`, and the accept loop is blocked while a client is served |
| Silence | a push is sent only when a world snapshot exists, so in a **menu the agent is silent but alive** (it still answers `ping`) |
| Stale data | the bridge thread re-sends the **last snapshot** every interval, even if the game thread stopped |
| Send | blocking `send()` with `MSG_NOSIGNAL` |
| Tick | `t` in each push is the **game-tick counter** (not a time) |

## 2. What can go wrong — and what this branch does about it

Each ✅ is covered by `AgentClientProtocolTest`, which runs the real `AgentClient` against `FakeAgent`
(a faithful in-process model of the native bridge) over real loopback sockets.

| # | Symptom | Cause (evidence) | Status |
|---|---|---|---|
| 1 | Agent never attaches if the game starts **after** the app | `AgentRuntime.autoStart` probed **once** at app start | ✅ attach the sensor client immediately; it retries quietly and reconnects after a game restart |
| 2 | "Connected" but no data | the kernel completes the TCP handshake of a connection that is only queued in the backlog; the agent serves one client at a time | ✅ `LinkState`: nothing counts until the agent actually answers |
| 3 | Reconnect churn while the game sits in a menu | "no data = dead" is wrong: the agent is silent, not dead | ✅ liveness = *any* line; the client pings every second and the agent pongs |
| 4 | Overlay/ESP keeps drawing from a **frozen or paused** game | the bridge thread keeps re-sending the last snapshot, so arrival time looks fresh | ✅ freshness follows the pushed tick `t` advancing; `STALLED` state; the camera and audit ignore stalled data |
| 5 | Tight reconnect loop with a wrong token or a busy agent | backoff was reset whenever a connection *ended* | ✅ backoff resets only after a **valid push**; a refused token backs off up to 30 s |
| 6 | Bridge thread stuck if the app is slow/frozen | blocking `send()` with no timeout | ⚠️ native: see §5 |
| 7 | A zombie client locks the app out | one client at a time, and the old connection is never replaced | ⚠️ native: see §5 |
| 8 | Link silently dies when the app is backgrounded | Android 14+ freezes cached apps ~10 s after they become cached and **terminates their TCP sockets** (AOSP *Cached apps freezer*) | ✅ the client reconnects by itself; keep the app's foreground service running while playing (it is, whenever the relay is on) |
| 9 | No way to tell *why* it is not working | no measurements | ✅ `BridgeHealth` + `.camera` bridge line (below) |

### Platform (Android 17) — verified against developer.android.com, updated 2026-10-01

* **Same-profile loopback is unaffected**, whatever the app targets. The bridge and the relay stay on `127.0.0.1`.
* **Cross-profile loopback is blocked for all apps.** Install the app and the patched game in the **same user
  profile** (not a work profile / second space / secure folder).
* **LAN traffic is gated for apps that target SDK 37**: sending/receiving UDP broadcast needs
  `ACCESS_LOCAL_NETWORK`, and without it packets are **silently dropped**, no error. The relay advertises itself
  by broadcasting to `255.255.255.255:19132` (`LanBroadcaster`), so bumping `targetSdk` to 37 without the permission
  would make the `rubidium` entry vanish. Today `targetSdk = 35` (implicit grant). `PlatformTripwireTest` fails the
  build if someone bumps it without declaring the permission. Android's own guidance is *not* to declare it before
  targeting 37.
* The February 2026 **preview** docs described a stricter cross-app rule (a `USE_LOOPBACK_INTERFACE` permission with
  mutual consent). It is **not** in the final Android 17 docs, which only keep the cross-profile block — an example of
  why to re-read the live page. Re-check when Android 18 previews appear.
* Whether two different apps may connect to each other's *abstract Unix sockets* depends on the device's SELinux
  policy (**not verified**) — which is why TCP loopback stays the transport.

## 3. Reading the numbers

`.camera` prints (and `Downloads/baba.txt` logs) one line, e.g.
`link=LIVE pushes=4821 rate=19.9Hz gapP95=61ms rtt=1.3ms connects=1 timeouts=0 stalls=0 rejected=0 parseErrors=0 proto=legacy`

| Field | Healthy | If not |
|---|---|---|
| `link` | `LIVE` while playing; `TCP_UP` in a menu | `OFFLINE`: game not running or agent not up · `REJECTED`: token mismatch · `STALLED`: game paused/frozen |
| `rate` | ≈ 20 Hz | far lower: the game thread is slow, or the app's reader is starved |
| `gapP95` | < ~80 ms | hitches: look at the game, not at the transport |
| `rtt` | a few ms (loopback) | tens of ms: the app process is starved |
| `connects` / `timeouts` | 1 / 0 per session | many: something keeps killing the link (freezer, another client, crashes) |

**Where latency really comes from:** the transport is not the bottleneck. A state line is ~1 KB plus ~128 B per
actor (≤ 48 actors ≈ 7 KB) at 20 Hz — a few hundred KB/s at worst on loopback. The delays that show up on screen
are the **sampling interval (50 ms)** and the **display refresh (16.7 ms)**. The fix for visible lag is prediction
and a higher camera push rate (§4), not a different socket.

## 4. Protocol — additive changes (backward compatible; the app already understands them)

* `proto` (int) and `agent` (build string) in each state push; absent ⇒ "legacy". Rules: additive changes keep the
  number, a breaking change bumps it, unknown fields and unknown caps are ignored, unknown ops answer `ok:false`.
* Keep `t` = game-tick counter and **never fabricate it in the bridge thread** — the app treats "`t` stopped
  changing" as "the game stopped".
* Later, split the streams: `camera` at render rate (small, 60 Hz) and `state` at 20 Hz.

## 5. What the native side should do (Eclient-Attach — not changed here, no NDK to test with)

1. Add `"proto":1,"agent":"<build>"` to every state push.
2. **Send with a timeout** (`SO_SNDTIMEO` ≈ 200 ms); on timeout drop the frame — latest wins — instead of blocking.
3. **Newest client wins**: when a new authenticated client connects, close the old one, so a zombie cannot lock the app out.
4. Always answer `ping`, including with no snapshot and while the game tick is frozen.
5. Compare the token in constant time (minor).

## 6. Authentication

The token file in shared storage is weak: any app that can read it can talk to the agent, and any app with
`INTERNET` can reach the port. That is **acceptable while the agent is read-only** (the worst case is leaking your
own position). It is **not acceptable once the agent can act**. Before any actuating intent exists, upgrade to one
of:

* **Binder + a signature-level permission** (both APKs signed with the same *private* key): enforced by the OS, no
  tokens. Needs a key that is not the public debug key currently used to re-sign the patched game.
* **Unix domain socket + `SO_PEERCRED`** (`LocalSocket.getPeerCredentials`): the kernel tells the agent the peer's UID.
  Only if the target devices' SELinux policy allows app-to-app connects (test first).
* At minimum: a **per-session token** handed over through app-private storage, never a file in Downloads.

## 7. Blueprint for a memory-based KillAura (later — nothing of this is built)

The hybrid-lite experiment comes first: it establishes the sensor path, the health metrics and the ownership
principle. When KillAura moves into memory, build it like this:

1. **The agent owns the loop, the app owns the policy.** Aim and swing decisions are sub-frame; a 50 ms round trip
   misses. The agent runs the aura tick in-process; the app only sends *policy* (range, target filter and priority,
   delay, hurt-time rule, on/off) at a low rate. This is already how `docs/MODULE_SDK.md` rule 4 reads.
2. **One owner per action capability.** `Attack` is owned by `PACKETS` **or** `AGENT`, chosen per module (a
   "Backend" setting), never both — that is the double-action hazard in a different coat. The app disables its
   packet KillAura when the agent owns `Attack`; the agent refuses intents from a non-owner (`claim`/`release`
   handshake). Show it in chat (`.owner`), like `.camera`.
3. **Fail safe on link loss.** With actions, a silent link must mean *stop*, not "keep doing the last thing". The agent
   runs a **dead-man switch**: if no valid line arrives within ~1.5 s it disables app-driven features. This matters
   on Android: the freezer can suspend the app (§2 #8) while the game keeps running.
4. **Intent semantics.** Every intent has an `id` and a `ttlMs` (dropped if not executed in time), is idempotent, and
   is acknowledged `queued → executed | failed | dropped` (the agent already counts these). Rate-limit per intent.
5. **Don't mix worlds.** Memory entity positions (what the client renders) and packet positions (what the server
   says, delayed) differ. A module uses **one** source, decided by ownership — never an average.
6. **Test it before it touches a game:** extend `FakeAgent` with the claim/dead-man/ttl behaviour and write the same
   kind of link-loss tests that exist today.
7. Prerequisites still missing in the agent: rotation write (underived) and a verified attack path under server
   validation. Do the stronger authentication (§6) **first**.

## 8. Decisions

* Transport stays **TCP loopback + NDJSON** (works across apps everywhere, exempt from the Android 17 changes).
* The app attaches the sensor client at start and never depends on start order.
* Freshness = the game advancing, never bytes arriving.
* No actuating intents until authentication is upgraded and ownership exists.
