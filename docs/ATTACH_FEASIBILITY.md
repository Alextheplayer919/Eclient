# Feasibility Study: From Proxy Client → "Attached" Client

Branch: `attach-experiment` — research + planning only. **No production code is touched here.**
Date: 2026-09-15

---

## 1. What Eclient is today (audit of core + modules)

### 1.1 Codebase shape

| Area | Files | Lines | Role |
|---|---|---|---|
| `core/` (relay + tracker) | 15 | ~3 205 | The proxy: Netty/Cloudburst RakNet relay + packet interception |
| `events/` | 2 | ~135 | `PacketEventBus` + `PacketEvent` (publish/replace/cancel) |
| `module/` | 77 | ~13 199 | All cheats (combat, movement, visual, world, misc, social) |
| `utils/` | 16 | ~3 273 | Rotation/chunk/inventory/placement helpers, trackers |
| `ui/` (dashboard + overlay) | 6 | ~4 917 | Compose dashboard + in-game overlay menus (3 styles) |
| `auth/` | 5 | ~980 | Microsoft/Xbox token login |
| `config/` + `session/` | 3 | ~633 | Session + persistence |
| **Total** | **~125** | **~26 400** | |

### 1.2 How the proxy mode works today

```
Minecraft (game)  ──UDP──►  RubidiumRelay :19150  ──UDP──►  Real server
                                   │
                        PacketEventBus.publish()
                                   │
                ◄── modify / cancel / replace ──
```

- `RubidiumRelay` opens a **local Bedrock server socket** (Cloudburst `bedrock-connection` 3.0.0.Beta6) — the game connects to *it*, thinking it's a LAN/normal server.
- `RubidiumRelaySession` dials the **real remote server** and forwards packets in both directions.
- Every packet passes through `PacketEventBus`, where modules may **inspect, mutate, cancel or replace** it (`cancelAndReplace`). This is how KillAura, Scaffold, AntiKnockback, etc. work: they rewrite `PlayerAuthInputPacket`, `ItemStackRequestPacket`, etc. on the wire.
- `EntityTracker` **reconstructs the whole game world** from inbound packets (spawns, moves, removals) because the proxy never sees the real game state — it *infers* it. Modules then read `EntityTracker.selfX/selfYaw/...` instead of actual game memory.
- Visual modules (ESP, ArrayList, HUD) draw with the **Android overlay** (`OverlayService`, `ESPOverlayView`) rendered on top of the game window — the game itself is never modified.

### 1.3 Module coupling analysis (how much depends on the proxy?)

- **9 files** use `PacketListener`/`PacketEventBus` directly — the "hardcore" packet modules (KillAura family, Criticals, AntiKnockback, Nuker…).
- **65 files** read `EntityTracker` — nearly the entire client depends on the *packet-reconstructed world state*. This is the single largest coupling point.
- **30 files** reference the relay session/transport (`currentSession`, `sendToServer`, bounds).
- The **UI layer is ~99% proxy-agnostic** — it's an overlay making WinAPI-free Compose windows; it doesn't care where data comes from.
- Kotlin multiplatform: everything is pure Kotlin/JVM + Android. **No JNI, no NDK, no C++ anywhere in the project today.**

### 1.4 Verdict of the audit

The client is **deeply packet-centric by design**: brains = packet MITM, eyes = EntityTracker, face = overlay. That's exactly why it can run **stock, unmodified Minecraft from the Play Store** — nothing inside the game process, **no root, no patched APK**, trivially installable. Any "attach" rewrite is not a port of a few files — it's a **new backend for the same UI**: EntityTracker becomes "read real memory", PacketEventBus becomes "hook game's network/input functions", overlay stays.

---

## 2. Research: how do attached Bedrock clients work in 2026?

### 2.1 Windows — DLL injection (the mature path)

- **BedrockBaritone** (Vman50, C++17, open source) — full-featured injected client for Windows Bedrock. Architecture worth copying *conceptually*:
  - **No hardcoded offsets** — everything resolved at runtime by **byte-pattern signature scanning** ("sigscanning") inside the game's .exe/.dll.
  - **MinHook** for function trampolines; ModuleMgr registry like ours; ImGui/DX12 overlay.
  - Key symbols scanned: `ClientInstance`, `LocalPlayer`, `BlockSource::getBlock`, `PacketSender::sendToServer`, `MinecraftPackets::createPacket`, `GameMode::attack`, actor-list getters, worldToScreen. Notice the mapping: these are the in-process equivalents of our `PacketEventBus`, `EntityTracker`, `MathUtil/dist`, and ESP projection.
  - Sources: github.com/Vman50/bedrockbaritone
- **Horion** (open source, OG Bedrock cheat) — same DLL-injection + pattern-scan + hook architecture, mature docs on ClientInstance retrieval. Sources: github.com/horionclient/Horion (+ DeepWiki docs).
- **Onix Client** (closed, commercial) — external launcher injects a single DLL into the game at launch. Proof the model sustains a userbase; nothing architectural to learn beyond that.

### 2.2 Android — our actual platform — the realistic attach paths

**Path A — LeviLaunchroid ("launcher preloader") — RECOMMENDED for the experiment.**
- Open-source Android launcher for Bedrock (ARM64, Android 9+): *imports the official Minecraft APK and runs it without re-installing*, with **native .so module loading via a "Preloader" API** — load shared libraries into the game process, receive input callbacks, install hooks, apply patches.
- An open-source native mod already exists as a reference implementation: **BedrockTools** (C++, xmake/NDK r28c) — builds `libBedrockTools.so` + a `.levipack` that LeviLaunchroid installs. It demonstrates exactly the lifecycle we'd need.
- License-clean: requires owning the game on Google Play (Eclient's current stance is the same).
- Sources: levilaunchroid.levimc.org · github.com/LiteLDev/LeviLaunchroid · github.com/QYCottage/BedrockTools

**Path B — Patch and re-sign the Minecraft APK.**
- Insert `System.loadLibrary("ourlib")` into the launcher/Activity smali (or add an ELF `DT_NEEDED` entry to an existing game lib with LIEF), rebuild, re-sign. Tools: apktool + apksigner, or automated frida-gadget injectors (Gadgater, frida_gadget_apk_injector — the .so-injection mechanics are identical, we'd ship our own lib instead of Frida's).
- Pros: no third-party launcher, no root. Cons: re-patching **every Minecraft update**, signature mismatch vs. Google Play (license checks, Xbox sign-in edge cases), distribution headache. Toolbox for MCPE historically lived here (it shipped/hook-notify via root on old versions) — lots of brittle lore.

**Path C — Root + ptrace / Zygisk.**
- Inject a .so into the running game process with `ptrace` (the desktop PoC Swofty-Developments/MinecraftInjectionPOC shows the mechanics for JVM; same idea for ARM64) or a Zygisk module that hooks `nativeForkAndSpecialize`.
- Pros: clean attachment to an untouched stock game. Cons: most users don't have root → dead end for Eclient's "just install and play" audience.

**Path D — Xposed/LSPosed Java hooking.**
- Hook the game's Java layer only. Nearly everything that matters in Bedrock is **native** (the game logic lives in `libminecraftpe.so`), so LSPosed alone buys almost nothing. ☒

### 2.3 The hard truth: offsets & version churn

- Bedrock release builds are **stripped** — symbols have been largely absent since ~1.13 (mcpelauncher era) / partially present in some Android builds; the `bedrock-headers` project documents field-offset archaeology. Every client-update can shuffle offsets.
- The community answer is **runtime pattern scanning** (BedrockBaritone-style) with per-version fallback tables, exactly what Horion does on Windows. This is the deciding factor for **maintenance cost**, not injection mechanics.
- Update cadence risk is real: users on Reddit routinely get bricked for days by Bedrock version mismatches (1.21.100/114/120 wave, late 2025) — an attached client goes dark every time until sigs are refreshed, whereas the proxy survives most updates untouched (protocol-cloudburst absorbs them).

### 2.4 What attaching *buys* over the proxy

| Proxy (today) | Attached (goal) |
|---|---|
| World state **inferred** from packets (EntityTracker guesses, lags, breaks silently) | World state **read directly** from game memory — exact, instant |
| Inventory = packet sniffing only | Real inventory, container content, hotbar state |
| Visuals = overlay drawn *on top* (no depth, no occlusion) | True in-world rendering hooks possible |
| Rotation hacks = spoof `PlayerAuthInputPacket` (server-side detectable, rubber-band fights) | Direct `LocalPlayer`/input write — smoother, server sees "normal" packets |
| Survives most game updates | Breaks per-update until sigs refreshed |
| Zero touch to the game | New attack surface for anti-cheat / Play Integrity |
| No root/no repack — works stock | Needs LeviLaunchroid or patched APK |

---

## 3. Proposal for `attach-experiment`

**Phase 0 (this branch, now):** research doc + threat model. Decide the target: **LeviLaunchroid Preloader .so** written in Kotlin/Native or C++, exposing a tiny C ABI.

> ⚠️ **Philosophy note (owner directive, 2026-09-15): LeviLaunchroid is scaffolding, NOT a dependency.**
> It exists in phases 1–2 only as the *fastest legal vehicle to inject a test .so into a stock game* and verify the pattern-scanning/offset/datapath work, because building our own injection plumbing would burn weeks before we even know whether the risky part works. The end product must be a **standalone Eclient APK** (or Eclient-issued component) — no LeviLauncher on the user's phone. That means phases 3–4 either (a) fork the minimal attach machinery (LeviLaunchroid and the preloader SDK are both Apache-2.0, so a clean-room-or-attributed embed inside Eclient itself is legal) or (b) ship a small installer-side patcher that emits a signed, enabled APK without any third-party launcher present. The decision point is explicitly inside Phase 4, after data path is proven.

**Phase 1 — "Hello, memory":** minimal native lib that loads inside the game via LeviLaunchroid, finds `ClientInstance` by pattern scan (port of bedrockbaritone sigs to ARM64), reads local-player position, and prints it to logcat. No cheats yet. *This is the make-or-break feasibility gate.* Status: **code done** (`attach/`), CI-built `.levipack` passed.

**Phase 2 — bridge:** pipe the in-process data to the existing Kotlin side (UNIX socket / JNI / binder) and re-point a **read-only** module (e.g., ArrayList or ESP coords) at it — existing overlay UI unchanged, proving the hybrid: game-attached backend + current UI.

**Phase 3 — write path:** hook `sendToServer`/analogous native function to recreate the packet-mutation surface (KillAura-family proof), or write rotation directly into LocalPlayer. Only then decide: full migration, permanent hybrid, or stay proxy.

**Phase 4 — decoupling (owner-required):** remove LeviLaunchroid dependency from the end state. Options to evaluate: in-app `.so` injection into the game process spun up from a forked launcher core (Apache-2.0 → permissible), or a one-tap installer side-component that produces a standalone signed APK. Criteria: no external launcher on the user's phone, no cracked/unsigned APKs, license requirement unchanged.

**Non-goals for now:** replacing the overlay UI, removing the relay code, any root-only solution, distributing patched game APKs, making LeviLaunchroid part of the shipped product.

**Risk register:** pattern-scan failure on the internal build (→ Phase 1 gate), per-update breakage cadence, Play Integrity/anti-cheat attention vs. the proxy's invisibility, maintenance of a second (native) toolchain (NDK + xmake + ARM64 sigs) the project doesn't currently have.

---

*Sources: BedrockBaritone (github.com/Vman50/bedrockbaritone), Horion (github.com/horionclient/Horion + deepwiki.com/horionclient/Horion), LeviLauncher docs (levilaunchroid.levimc.org, github.com/LiteLDev/LeviLaunchroid), BedrockTools (github.com/QYCottage/BedrockTools), Onix Client (onixclient.com), bedrock-headers (github.com/mcbedrock/bedrock-headers), mcpe-launcher symbol-stripping discussion (r/linux_gaming, 2019), frida-gadget injectors (github.com/wsdx233/Gadgater, github.com/jeanzuck/frida_gadget_apk_injector), MinecraftInjectionPOC (github.com/Swofty-Developments/MinecraftInjectionPOC).*
