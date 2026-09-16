#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Eclient attach patcher — phase 1 (go/no-go gate support tool)
#
# Turns a stock Minecraft Bedrock APK into a signed APK that loads
# libeclient_attach.so at startup — WITHOUT LeviLaunchroid, WITHOUT any
# third-party launcher on the phone afterwards. The produced APK is
# self-contained (stock game + our .so + one smali line).
#
# What it does, mechanically:
#   1. apktool d  — decode the game APK
#   2. Inject `const-string v0, "eclient_attach" +
#            invoke-static {v0}, Ljava/lang/System;->loadLibrary(...)V`
#      at the top of the game's main activity `onCreate` (smali edit)
#   3. Copy libeclient_attach.so into lib/arm64-v8a/
#   4. apktool b + zipalign + apksigner (our own debug keystore, auto-made)
#
# Usage:
#   ./patch.sh /path/to/base.apk /path/to/libeclient_attach.so [out.apk]
#
# Requirements on PATH: apktool, zipalign, apksigner (or jarsigner)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE_APK="${1:-}"
OUR_SO="${2:-}"
OUT_APK="${3:-eclient_attached.apk}"
KEYSTORE="${KEYSTORE:-$HOME/.eclient/patch-debug.keystore}"

fail() { echo "[patch] ERROR: $*" >&2; exit 1; }
step() { echo "[patch] $*"; }

[[ -f "$BASE_APK" ]] || fail "game APK not found: $BASE_APK"
[[ -f "$OUR_SO"   ]] || fail "libeclient_attach.so not found: $OUR_SO"
command -v apktool >/dev/null  || fail "apktool not on PATH"
command -v zipalign >/dev/null || fail "zipalign not on PATH"
command -v apksigner >/dev/null || command -v jarsigner >/dev/null \
    || fail "need apksigner (build-tools) or jarsigner (JDK)"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── 1. decode ────────────────────────────────────────────────────────────────
step "1/6 decoding $BASE_APK"
apktool d -f -o "$WORK/decoded" "$BASE_APK" >/dev/null

DECODED="$WORK/decoded"
[[ -f "$DECODED/apktool.yml" ]] || fail "decode produced no apktool.yml"

# ── 2. find the launcher's entry activity ──────────────────────────────────
step "2/6 locating entry activity"
MANIFEST="$DECODED/AndroidManifest.xml"
# First MAIN+LAUNCHER activity name; fall back to the <application> name attr.
ENTRY=$(grep -oE 'android:name="[^"]+"' "$MANIFEST" | head -1 | sed 's/android:name="//;s/"//')
if [[ -z "$ENTRY" ]]; then
  ENTRY=$(grep -oE '<application[^>]*android:name="[^"]+"' "$MANIFEST" \
          | sed 's/.*android:name="//;s/".*//' | head -1)
fi
[[ -n "$ENTRY" ]] || fail "could not determine entry class from manifest"
ENTRY_PATH="${ENTRY#.}"                        # strip leading '.'
ENTRY_PATH="${ENTRY_PATH//.//}"                # dots -> slashes
if [[ "$ENTRY_PATH" != /* ]]; then true; fi    # keep as dir-path fragment

SMALI_FILE=""
for smali_dir in "$DECODED"/smali*; do
  [[ -d "$smali_dir" ]] || continue
  candidate="$smali_dir/${ENTRY_PATH#*/}.smali"
  # Also allow manifest-name without package prefix (rare dotted shorthand)
  [[ -f "$candidate" ]] && SMALI_FILE="$candidate" && break
done
[[ -n "$SMALI_FILE" ]] || fail "entry smali not found for $ENTRY — check manifest structure"
step "    entry smali: $SMALI_FILE"

# ── 3. inject System.loadLibrary into the FIRST onCreate/onCreate override ──
step "3/6 injecting loadLibrary into onCreate"
python3 - "$SMALI_FILE" <<'PYEOF'
import re, sys

path = sys.argv[1]
src  = open(path, encoding="utf-8").read()

# Smali layout at the start of a method (baksmali output):
#     .method ... onCreate(...)V
#         .locals N
#
#         .param p1, "..."          (with blank lines / comments interleaved)
#         .annotation ...
#         .end annotation
#         .prologue
#         .line 123
#         <first real instruction>
#
# Register strategy: bump .locals by 1 → fresh topmost local index N, use it
# for the injected const-string. Insert AFTER all declaration pseudo-instr.
m = re.search(
    r"([ \t]*\.method [^\n]*onCreate\([^)]*\)[^\n]*\n"
    r"[ \t]*\.locals (\d+)\n"
    r"(?:[ \t]*\n|[ \t]*# [^\n]*\n|"
    r"[ \t]*\.param [^\n]*\n|"
    r"[ \t]*\.annotation[\s\S]*?[ \t]*\.end annotation\n|"
    r"[ \t]*\.prologue\n|"
    r"[ \t]*\.line \d+\n)*)",
    src,
)
if not m:
    sys.exit("[patch] ERROR: no onCreate/.locals found in " + path)

block = m.group(1)
n = int(m.group(2))
payload = (
    f'    const-string v{n}, "eclient_attach"\n\n'
    f'    invoke-static {{v{n}}}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V\n\n'
)
rebuilt = block.replace(f"    .locals {n}\n", f"    .locals {n + 1}\n", 1) + payload
src = src.replace(block, rebuilt, 1)

open(path, "w", encoding="utf-8").write(src)
print("[patch]    injected System.loadLibrary(\"eclient_attach\") into", path)
PYEOF

# ── 4. drop the .so in ───────────────────────────────────────────────────────
step "4/6 placing libeclient_attach.so"
mkdir -p "$DECODED/lib/arm64-v8a"
cp "$OUR_SO" "$DECODED/lib/arm64-v8a/libeclient_attach.so"

# ── 5. rebuild + align ───────────────────────────────────────────────────────
step "5/6 rebuilding APK"
UNSIGNED="$WORK/unsigned.apk"
ALIGNED="$WORK/aligned.apk"
apktool b -o "$UNSIGNED" "$DECODED" >/dev/null
zipalign -f -p 4 "$UNSIGNED" "$ALIGNED"

# ── 6. sign ──────────────────────────────────────────────────────────────────
step "6/6 signing"
if [[ ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v -keystore "$KEYSTORE" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -alias eclient_patch -storepass eclient_patch_pass -keypass eclient_patch_pass \
    -dname "CN=EclientAttachDebug, OU=Experiment, O=Eclient, C=US" >/dev/null 2>&1
  step "    generated fresh debug keystore at $KEYSTORE"
fi

if command -v apksigner >/dev/null; then
  apksigner sign --ks "$KEYSTORE" --ks-pass pass:eclient_patch_pass \
    --key-pass pass:eclient_patch_pass --out "$OUT_APK" "$ALIGNED"
  apksigner verify --verbose --print-certs "$OUT_APK" >/dev/null && \
    step "    signature verified ✓"
else
  cp "$ALIGNED" "$OUT_APK"
  jarsigner -keystore "$KEYSTORE" -storepass eclient_patch_pass \
    -keypass eclient_patch_pass "$OUT_APK" eclient_patch >/dev/null 2>&1 \
    && step "    jarsigner applied (no apksigner) — prefers testing only"
fi

step "DONE → $OUT_APK"
step "Install:  adb install -r $OUT_APK"
step "Watch:    adb logcat -s EclientAttach"
