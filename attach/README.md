# Eclient Attach — Phase 1 (experiment, launcher-free)

**Branch-only experiment.** Nothing here is referenced by `app/`, the Gradle
build, or the proxy code. See `docs/ATTACH_FEASIBILITY.md` for the plan.

## Architecture — NO LeviLaunchroid anywhere

```
Your PC runs:  patch.sh  →  stock-game.apk + libeclient_attach.so
                            └──► eclient_attached.apk  (re-signed)
                                  │
                                  ▼ install
                            Game launches, our .so is inside its process
                                  │
                                  ▼
                     pattern-scan + hook + read real memory
                                  │
                                  ▼
                     adb logcat -s EclientAttach   →   pos=(x, y, z)
```

The game does the loading itself. After `patch.sh` finishes, **there is no
second app, no launcher, nothing extra on the device** — just one modified
game APK (signed with our own keystore, which it auto-generates).

## What the probe does (deliberately boring — it's a gate, not a cheat)

1. `__attribute__((constructor))` fires the moment the game dlopens the `.so`
2. A detached pthread waits for `libminecraftpe.so` to map, then ARM64
   byte-pattern scans (adapted from open-source **BedrockTools**, Apache-2.0)
   for `ClientInstance::update` + `ClientInstance::getLocalPlayer`
3. **Dobby** (MIT hook engine, statically linked) detours the update function
   to capture the live `ClientInstance*`
4. Every ~10 s reads position + rotation from
   `StateVectorComponent` / `ActorRotationComponent` and logs to logcat
5. If a signature misses: loud log line, zero writes — stock game runs on

That last point is exactly the *compatibility test*: if v1.26.x of the game
still matches these patterns, we know the attach path is viable and can move
to phase 2. If not, we update patterns and re-test — same as every injected
client on Windows.

## Build the .so

Built by **`.github/workflows/attach_build.yml`** on pushes to this branch —
download `eclient_attach-so` from the run artifacts. Locally:

```bash
cmake -S attach -B attach/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<28.x>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
cmake --build attach/build       # → attach/build/libeclient_attach.so
```

## Patch the game APK

```bash
# Needs on PATH: apktool, zipalign, apksigner or jarsigner (Java).
./attach/patcher/patch.sh /path/to/base.apk \
    attach/build/libeclient_attach.so  eclient_attached.apk

adb install -r eclient_attached.apk
```

Notes:
- The script auto-makes `~/.eclient/patch-debug.keystore` on first run.
- `apksigner` produce a proper v2/v3 signature; the jarsigner fallback makes
  v1-only (older Android will still install, but prefer apksigner when present).
- If the game is already installed with a different signature, `adb install -r`
  fails — uninstall the stock game first (settings are independent worlds/data,
  back up if needed), then install ours.

## Gate verdict criteria

| Result | Meaning |
|---|---|
| `pos=(…, …, …) rot=(…, …)` every ~10 s while in a world | **GO** → phase 2 |
| `signature miss (update=0 player=0)` in logcat | version mismatch → refresh patterns, one retry, then judge |
| Game crashes at launch | hook toolchain wrong for this device → investigate Dobby target; worst case NO-GO |

## Layout

```
attach/
├── CMakeLists.txt       # NDK project; fetches & statically links Dobby
├── README.md
├── patcher/
│   └── patch.sh         # one-command game APK patcher (apktool + sign)
└── src/
    ├── probe.cpp        # the probe itself (scan → DobbyHook → log)
    ├── sigscan.cpp      # self-contained /proc/self/maps wildcard scanner
    └── sigscan.h
```

## Hook engine

**And64InlineHook** (github.com/Rprop/And64InlineHook) — the classic single-purpose
ARM64 inline hooker used by the MCPE/BedrockTools community.
- Two-file library, MIT licensed, vendored under `src/hook/`.
- Compiled directly into `libeclient_attach.so` — **no build-time network
  fetches, no external native .so to load** (the earlier Dobby/FetchContent
  design is gone; Dobby's upstream build is unmaintained and drifts).
- API is a one-liner: `A64HookFunction(target, replacement, &original)`,
  matching how mod menus traditionally hook packet senders in `libminecraftpe.so`.
