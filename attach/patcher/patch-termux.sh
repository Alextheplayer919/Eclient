#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Eclient attach patcher — TERMUX EDITION (patch on the phone itself, no PC).
#
# Same mechanics as patch.sh (decode → smali inject loadLibrary("eclient_attach")
# → place .so → rebuild → sign), adapted for Termux:
#   pkg install apktool apksigner openjdk-17
# (zipalign is intentionally skipped — unaligned APKs install fine; alignment
# only saves some RAM pages, which we can re-add later if profiling demands.)
#
# Usage (in Termux):
#   ./patch-termux.sh /sdcard/Download/base.apk /sdcard/Download/libeclient_attach.so [out.apk]
#
# Afterwards install the result from the Files app (tap the APK), or:
#   termux-open --view "$PWD/out.apk"
# When Minecraft updates: re-download the new base APK and run this again.
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
command -v apktool >/dev/null   || fail "apktool missing — pkg install apktool"
command -v apksigner >/dev/null || fail "apksigner missing — pkg install apksigner"
command -v keytool >/dev/null   || fail "keytool missing — pkg install openjdk-17"
command -v python3 >/dev/null   || fail "python3 missing — pkg install python"

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
if ! ENTRY=$(python3 - "$MANIFEST" <<'PYEOF'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()

m = re.search(r'^\s*<manifest[^>]*\bpackage="([^"]+)"', src, re.M)
pkg = m.group(1) if m else ""

# Every <activity ...> element. A regex can't use "/>" as the terminator
# (children like <action .../> would end the block early), so walk start tags
# and find each element's real close.
for m in re.finditer(r'<activity\b', src):
    start = m.start()
    gt = src.find('>', start)
    if gt == -1:
        break
    if src[gt - 1] == '/':
        block = src[start:gt + 1]          # self-closing, no children
    else:
        end = src.find('</activity>', gt)
        block = src[start:end] if end != -1 else src[start:]
    if 'android.intent.action.MAIN' not in block:
        continue
    if 'android.intent.category.LAUNCHER' not in block:
        continue
    name = re.search(r'\bandroid:name="([^"]+)"', block)
    if not name:
        continue
    name = name.group(1)
    if name.startswith('.'):
        name = pkg + name
    elif '.' not in name:
        name = pkg + '.' + name
    print(name)
    sys.exit(0)

# Fallback: application-level entry class (no explicit launcher activity found)
app = re.search(r'<application[^>]*\bandroid:name="([^"]+)"', src)
if app:
    name = app.group(1)
    if name.startswith('.'):
        name = pkg + name
    print(name)
    sys.exit(0)

sys.exit(1)
PYEOF
); then
  fail "could not determine launcher activity from manifest"
fi
ENTRY_PATH="${ENTRY//.//}"

SMALI_FILE=""
for smali_dir in "$DECODED"/smali*; do
  [[ -d "$smali_dir" ]] || continue
  candidate="$smali_dir/${ENTRY_PATH}.smali"
  [[ -f "$candidate" ]] && SMALI_FILE="$candidate" && break
done
[[ -n "$SMALI_FILE" ]] || fail "entry smali not found for $ENTRY — check manifest structure"
step "    entry smali: $SMALI_FILE"

# ── 3. inject System.loadLibrary into onCreate ──────────────────────────────
step "3/6 injecting loadLibrary into onCreate"
python3 - "$SMALI_FILE" <<'PYEOF'
import re, sys

path = sys.argv[1]
src  = open(path, encoding="utf-8").read()

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

# ── 5. rebuild (NO zipalign on Termux — install works unaligned) ────────────
step "5/6 rebuilding APK (skip zipalign — Termux)"
UNSIGNED="$WORK/unsigned.apk"
apktool b -o "$UNSIGNED" "$DECODED" >/dev/null

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

apksigner sign --ks "$KEYSTORE" --ks-pass pass:eclient_patch_pass \
  --key-pass pass:eclient_patch_pass --out "$OUT_APK" "$UNSIGNED"
apksigner verify --verbose --print-certs "$OUT_APK" >/dev/null && \
  step "    signature verified ✓"

step "DONE → $OUT_APK"
step "Install: open $OUT_APK in the Files app (or: termux-open --view \"$OUT_APK\")"
step "Verify feed: launch Eclient app, connect the session, watch DiagLog for"
step "           'NativeFeed ... connected'; stock fallback = everything untouched."
