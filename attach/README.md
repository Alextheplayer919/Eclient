# Eclient Attach Probe — Phase 1 (experiment)

**Branch-only experiment.** Nothing here is referenced by `app/`, the Gradle
build, or the proxy code. See `docs/ATTACH_FEASIBILITY.md` for the plan.

## What it is

A minimal **`preload-native` module for LeviLaunchroid** (open-source Android
launcher that injects native `.so` mods into the *exported official* Minecraft
APK at launch — no re-signing, no root). Purpose: the go/no-go gate for the
attach-client experiment.

It does **one** thing:

1. Pattern-scans `libminecraftpe.so` for `ClientInstance::update` and
   `ClientInstance::getLocalPlayer` (ARM64 wildcard signatures adapted from
   the open-source **BedrockTools** mod, Apache-2.0).
2. Detours the update function to capture the live `ClientInstance*`.
3. Every ~10 s reads the local player's position + rotation from the game's
   own memory (StateVectorComponent / ActorRotationComponent offsets) and logs
   it — tag `EclientAttachProbe` in logcat and in LeviLauncher's mod log.

Nothing else. No writes, no cheats, no UI. If signatures miss, it logs the
failure and exits clean — the game runs stock. That *is* the test.

## Gate verdict criteria

| Result | Meaning |
|---|---|
| Position lines appear while in a world | **GO** → phase 2 (bridge into Eclient UI) |
| `Signature resolution failed` in log | version mismatch → refresh patterns, retry once |
| Crash / freeze at load | **NO-GO** for now → stay proxy (main branch unaffected) |

## Build

Built by **`.github/workflows/attach_build.yml`** on pushes to the
`attach-experiment` branch — download `eclient_attach-levipack` from the
run artifacts. Locally:

```bash
cmake -S attach -B attach/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<28.x>/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release
cmake --build attach/build            # produces *.levipack
```

## Install & test

1. Install **LeviLaunchroid** (LiteLDev/LeviLaunchroid, needs an arm64 device,
   Android 9+, and a Play Store copy of Minecraft Bedrock).
2. In LeviLauncher: import your official MC APK.
3. Import the probe: **Mods → Import** → pick the `eclient_attach-*.levipack`
   (or drop `libeclient_attach.so` in manually).
4. Enable it, launch the game, load into a world.
5. Watch the proof: `adb logcat -s EclientAttachProbe:* Preloader:*` — you
   should see `pos=(…, …, …) rot=(…, …)` every ~10 s while walking.

## Layout

```
attach/
├── CMakeLists.txt        # standalone NDK project; fetches preloader-android 0.2.2
├── manifest.json.in      # LeviPack manifest (type: preload-native)
├── README.md             # this file
└── src/
    ├── main.cpp          # PL_REGISTER_MOD entry
    ├── EclientAttachMod.h
    └── EclientAttachMod.cpp
```
