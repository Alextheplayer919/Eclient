# WClient 1.21.130 work — patch series

Patch series for the **`Alextheplayer919/WClient`** fork (upstream `RetrivedMods/WClient`).

## Why the patches live in this repo instead of a WClient branch

The coding sandbox that produced them can push **only** to the branch it is bound to
(`arena/01a10620-eclient`). Its GitHub App installation does not include
`Alextheplayer919/WClient`, so every write there is refused:

```
remote: Permission to Alextheplayer919/WClient.git denied to Alextheplayer919.
fatal: unable to access 'https://github.com/Alextheplayer919/WClient.git/': 403
```

Read access works, pushes do not. To let the agent commit straight to the fork, add that
repository to the Arena GitHub App installation (GitHub → Settings → Installations →
configure → Repository access → add `WClient`). Until then, apply the patches yourself with
the two commands below — or drag them to whoever pushes.

## How to apply

The series is based on `f7b1a263` ("cleanup", 2026-08-21) — the current head of both the fork
and upstream. It has been verified to apply cleanly with `git am` on a pristine clone.

```bash
git clone https://github.com/Alextheplayer919/WClient.git
cd WClient
git checkout -b arena/1.21.130-modules
git am /path/to/patches/*.patch
git push -u origin arena/1.21.130-modules
```

Then open a PR on the fork (or merge straight into `main` if you trust it).

Applying by hand instead: each patch is a normal `git diff` — `git apply` also works.

## What the four patches do

| # | Commit | Change |
|---|---|---|
| 0001 | `fix(modules)` | `AntiCrystalModule` was a **complete no-op** (immutable `Vector3f.add()` result discarded). `NoClipModule` sent its vertical motion packet for the wrong entity id (`uniqueEntityId` instead of `runtimeEntityId`), so no-clip never moved. `KeyStrokesModule` (152 lines, 10 settings, plus its overlay) was never registered in `ModuleManager` and was unreachable — now registered. |
| 0002 | `fix(version)` | The version string existed in three disagreeing places (`"v1.21.132"` in the UI, `"1.21.120"` sent to the Realms API, `"1.21.130"` in the codec label). `MinecraftUtils` is now the single source of truth (`TARGET_PROTOCOL = 898`, `MC_VERSION = "1.21.130"`). |
| 0003 | `feat(motion)` | `MotionFlyModule` reimplemented with the LeHu/Testfly motion model from the other client: blocks-per-second speeds (`/20` per tick), the `sqrt(5.99)` vanilla horizontal clamp, up/down horizontal factors, glide, yaw-relative input transform with normalisation, and the 4.5-block anti-rubberband check. Collision-guard and movement-compliance helpers were **deliberately not** ported (they belong to subsystems this client does not have). A `Grant Fly Ability` setting (default off) keeps the old self-sufficient behaviour available. |
| 0004 | `build(ci)` | New `.github/workflows/build.yml`: builds debug + release APKs on every push to `main`/`arena/*`, on PRs and on demand; uploads both as artifacts; publishes a GitHub Release when a tag is pushed. No secrets needed — the committed `buildKey.jks` signs the build. Also fixes `buildAll` / `buildDebugAll`, which referenced a `:desktop:shadowJar` task for a module that does not exist in this repository, so both died before building anything. |

## Companion documents

* `WClient-MODULE-RESEARCH.md` — full audit of all 63 module files: the framework, every module's
  settings/packets/mechanism, the packet→module matrix, and the findings behind patch 0001.
* `WClient-ROTATION-PLAN.md` — design notes for the rotation prediction (miss fewer hits) and fast
  spin work: what the client tracks today, the `MoveEntityDeltaPacket` flag/delta facts, where to add
  angular velocity, the aim/attack gap, and a suggested order of work.

## Nothing was built or run

The sandbox has no JDK or Android SDK, and Maven Central / JitPack / `repo.opencollab.dev` are
egress-blocked there, so the patches are **static edits only** — they were not compiled. Syntax was
checked by hand and by brace/import analysis; the first CI run on the fork is the real compile test.
