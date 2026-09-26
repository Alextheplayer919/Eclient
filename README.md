# Eclient

A packet-level **proxy client for Minecraft: Bedrock Edition** (Android). The app runs as a local
client ↔ server relay: it sits on the wire between your game and the server, reads and writes real
Bedrock protocol packets, and powers **74 registered modules** across combat, movement, building,
and automation — plus a full in-game chat command system.

Release builds ship as `OxClient-*.apk` (internal app name).

![Modules](https://img.shields.io/badge/modules-74-blue)
![Platform](https://img.shields.io/badge/platform-Android-green)
![Protocol](https://img.shields.io/badge/Bedrock-packet%20proxy-orange)

---

## 🎯 Target version (1.21.111 / protocol 844)

This build targets **Minecraft Bedrock 1.21.111, protocol 844** — the same build
the attach runtime is pinned to, so the proxy and the in-game runtime describe the
same game version. One file owns the target: `core/relay/TargetVersion.kt`.

What that means in practice:

* **The relay introduces itself as 1.21.111.** The RakNet advertisement, the LAN
  pong and the session's pre-negotiation default all come from `TargetVersion`.
  They used to come from `CodecRegistry.getLatestCodec()` — the newest codec the
  vendored library contains — which made a 1.21.111 client ping a server that
  claimed to be 1.26.50.
* **Every client still negotiates its own codec.** `AutoCodecListener` reads the
  client's `RequestNetworkSettings` and picks the matching codec, item
  definitions and block palette for that protocol, so 1.21.111, 1.21.130 or 1.26.x
  clients all work against the same relay. The target only decides what the relay
  claims before it has seen a client.
* **Support is checkable, not assumed.** On every capture the log gets
  `target=1.21.111(protocol 844) resolved=…(protocol …) exact=true/false`, the
  definitions files actually chosen, and — per session — the negotiated result
  `client=<n> -> codec <n> <version>`. If the vendored library ever lacks 844, the
  fallback is logged as a warning instead of showing up later as packet errors.

## 🎮 Features

### ⌨️ In-game command system (prefix `.`)
Type commands straight into the chat box. Replies are **local-only** — nothing ever leaks into
public chat — and anything that isn't an exact registered command passes through as normal message.

| | |
|---|---|
| `.help [command]` | list commands / usage for one |
| `.coords [copy]` | show coords · copy to clipboard |
| `.toggle <module>` | partial, case-insensitive match |
| `.panic` | disable ALL modules instantly |
| `.list` | all modules + on/off state |
| `.config list/save/load/current` | settings management |
| `.friend add/remove/list · notify · msg` | social |
| `.enemy add/remove/list` | social |
| `.schem load/toggle/layer/nudge` | schematic control |
| `.build start/stop/status` | auto-build control |

### 🤝 Friends & Enemies (global, cross-server)
- **FriendGuard:** friends are physically un-attackable — enforced at the outgoing
  attack-packet choke point, so no combat module can bypass it. Ever.
- Global JSON lists, friend/enemy mutual exclusion, online-name canonicalization.
- Optional `/w` whisper on add/remove, toggleable with a customizable message.
- Rate-limited batch adds; offline players are skipped silently.

### 📐 Schematica + Auto Build
- Loads **`.litematic` and legacy `.schematic`** (multi-region merge, tight bit-packing fixed).
- Debug-drawer wireframe ghost + per-layer view + nudging.
- Auto Build prints the schematic for real — orientation-free placement, slabs and block
  states verified (field-tested on a live server).
- Schematics dashboard tab in the GUI: file list, active marker, delete, SAF import.

### 🪧 AutoSign • 🔦 AutoTorch
- **AutoSign** — writes your name + date on every sign you place (custom lines via settings).
- **AutoTorch** — keeps hostile mobs from spawning around you; drives placement from researched
  Bedrock light-level spawn rules.

### 📊 Module breakdown (74 registered)

| Category | Count | Examples |
|---|---|---|
| ⚔️ Combat | 21 | KillAura / LegitAura / TPAura, CrystalAura, AnchorAura, AutoTotem, AutoArmor |
| 🏃 Movement | 19 | MotionFly, LifeboatFly, NoClip, FreeCam, Scaffold, AntiKnockback |
| 👁 Visual | 15 | ESP, Xray, FullBright, Hitbox render, ArrayList |
| 🧍 Player | 7 | GodMode, AntiAFK, NoFallDamage |
| 🌍 World | 4 | Nuker, WeatherController, AntiCheat-probes |
| 🧩 Misc | 12 | AutoTravel, AutoMine, ChatSpammer, FlightProbe, PacketCollector |

### 💬 Community
Join the Discord: <https://discord.gg/AxxufgTdsx> — announcements, release notes, support.

---

## 📚 Documentation

Full internals live in [`docs/`](docs/) — packet-level notes on every sweep:

| File | What's inside |
|---|---|
| [`CHAT-COMMANDS.md`](docs/CHAT-COMMANDS.md) | command registry & dispatch contract |
| [`FRIENDS.md`](docs/FRIENDS.md) | friend system, FriendGuard choke, whisper pipeline, diagnostics log |
| [`AUTOSIGN.md`](docs/AUTOSIGN.md) | sign writing via `BlockActorDataPacket` |
| [`AUTOTORCH.md`](docs/AUTOTORCH.md) | Bedrock spawn rules research + light sim |
| [`SCHEMATICA.md`](docs/SCHEMATICA.md) | loaders, wireframe renderer, commands |
| [`AUTOBUILD.md`](docs/AUTOBUILD.md) | Auto Build planner/verifier |
| [`MODULES.md`](docs/MODULES.md) | catalog of every module |
| [`PROTOCOL.md`](docs/PROTOCOL.md) | Cloudburst packet usage inventory |
| [`CORE-ARCHITECTURE.md`](docs/CORE-ARCHITECTURE.md) | relay/session/session-tracking model |
| [`MELODY_MODULES.md`](docs/MELODY_MODULES.md) | native-side module notes |
| plus | `ANTI_CAMPER_PLAYBOOK.md`, `NO_LAGBACK_V3*.md`, `ANTI_CAMPER_*` |

---

## 🗺 Progress / roadmap

**✅ Shipped in v3.1**
- In-game command system (leak-proof dispatch, survives session re-arms)
- Global Friends/Enemies + absolute FriendGuard
- AutoSign + AutoTorch
- Schematica: `.litematic`/`.schematic` loaders, dashboard tab, Auto Build v2
  (placement printer, field-validated)

**🚧 In progress**
- Combat module overhaul (KillAura targeting/ranking revamp)
- More world automation (schem-aware builders, travel improvements)

---

## ⚠️ Disclaimer
For educational and private use. Using third-party client features on public servers likely
violate those servers' rules — you are responsible for how you use it.
