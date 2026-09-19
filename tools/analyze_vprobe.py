#!/usr/bin/env python3
"""analyze_vprobe.py — turn a FlightProbe baba.txt capture into verdict hints
for the NoLagback v3 verification checklist (docs/NO_LAGBACK_V3_VERIFICATION.md).

Usage:  python3 tools/analyze_vprobe.py baba.txt [--all-phases]

The log format:  HH:mm:ss.SSS [VPROBE] VP|v1|<RECORD>
Phases are delimited by MARK lines (each FlightProbe enable = next phase).
"""
import re
import sys
from collections import defaultdict

LINE = re.compile(r"^(\d{2}):(\d{2}):(\d{2})\.(\d{3}) \[VPROBE\] VP\|v1\|(.*)$")


def ms_of(h, m, s, ms):
    return ((int(h) * 60 + int(m)) * 60 + int(s)) * 1000 + int(ms)


def kv(body):
    out = {}
    for part in body.strip("|").split("|"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k] = v
    return out


def f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return float("nan")


def parse(path):
    recs = []  # (ms, kind, dict|raw)
    for line in open(path, errors="replace"):
        m = LINE.match(line.rstrip("\n"))
        if not m:
            continue
        t = ms_of(*m.groups()[:4])
        body = m.group(5)
        kind = body.split("|")[0] if "|" in body else body
        if kind == "":
            continue
        recs.append((t, kind.lstrip("|"), kv(body) if "=" in body else {"raw": body}))
    return recs


def ms_delta(a, b):  # b - a, tolerates day rollover crudely
    d = b - a
    if d < -12 * 3600 * 1000:
        d += 24 * 3600 * 1000
    return d


def segment(recs):
    """Split into phases by MARK; returns [(phase_no, host, start_ms, end_ms, [recs])]."""
    phases, cur = [], None
    for r in recs:
        t, kind, d = r
        if kind == "MARK":
            if cur:
                cur[3] = t
                phases.append(cur)
            cur = [d.get("n", "?"), d.get("host", "?"), t, t + 24 * 3600 * 1000, [r]]
        else:
            if cur is None:
                cur = ["0(pre)", "?", recs[0][0] if recs else 0, t, []]
            cur[4].append(r)
    if cur:
        cur[3] = recs[-1][0] if recs else 0
        phases.append(cur)
    return phases


def bucket(v, edges):
    lab = []
    for i, e in enumerate(edges):
        lo = "-inf" if i == 0 else str(edges[i - 1])
        lab.append((lo, str(e)))
    lab.append((str(edges[-1]), "+inf"))
    return lab


def hist(values, edges):
    counts = defaultdict(int)
    for v in values:
        for e in edges:
            if v <= e:
                counts[e] += 1
                break
        else:
            counts[float("inf")] += 1
    return dict(counts)


def report(phases, verbose):
    allrecs = [r for ph in phases for r in ph[4]]

    def corr_records(rs):
        return [(t, d) for t, k, d in rs if k in ("CORR", "CORRM")]

    print("=" * 78)
    print("FlightProbe analysis — NoLagback v3 verification checklist")
    print("=" * 78)

    # ── session inventory
    print("\n## Phases found (MARK-toggle boundaries)")
    for n, host, t0, t1, rs in phases:
        dur = ms_delta(t0, t1) / 1000.0
        nc = len(corr_records(rs))
        kinds = defaultdict(int)
        for _, k, _ in rs:
            kinds[k] += 1
        print(f"  phase {n:>3} host={host:<24} dur={dur:6.0f}s corr={nc:>3} "
              f"psync={kinds['PSYNC']:>3} mp={kinds['MP']:>3} sad={kinds['SAD']:>3} uat={kinds['UAT']:>3}")

    # ── item 5 / 4: StartGame settings
    sgs = [(t, d) for t, k, d in allrecs if k == "SG"]
    print("\n## Item 5+4 — authority mode & movement settings (StartGame)")
    if not sgs:
        print("  !! no SG records — probe was not enabled during join; re-run with probe on before joining")
    for t, d in sgs:
        print(f"  mode={d.get('mode','?'):<22} rewindHist={d.get('rewindHist','?'):<5} sabb={d.get('sabb','?')}")
    n161 = sum(1 for t, k, d in allrecs if k == "CORR")
    nreset = sum(1 for t, k, d in allrecs if k == "CORRM")
    print(f"  corrections seen: 161-style={n161}, MovePlayer-RESET-style={nreset}")
    if n161 > 0:
        print("  → flavor: V3 (server-auth WITH rewind). v3 design applies.")
    elif nreset > 0:
        print("  → flavor: legacy/PMMP (client-auth, plugin corrections). Governor = only mechanism.")
    else:
        print("  → no corrections observed in any phase (needed: illegal-movement phases P2–P4).")

    # ── item 1: tick meaningfulness
    lags = [f(d.get("lag")) for t, k, d in allrecs if k == "CORR" and d.get("lag", "-1") != "-1"]
    print("\n## Item 1 — 161.Tick meaningful?  (lag = authTick - corrTick)")
    if not lags:
        print("  no data")
    else:
        in140 = sum(1 for x in lags if 1 <= x <= 40)
        zero = sum(1 for x in lags if x == 0)
        over = sum(1 for x in lags if x > 40)
        srt = sorted(lags)
        print(f"  n={len(lags)} median={srt[len(srt)//2]:.0f} min={srt[0]:.0f} max={srt[-1]:.0f}")
        print(f"  in 1..40: {in140}  ==0: {zero}  >40: {over}")
        if in140 >= max(2, len(lags) // 2):
            print("  → MEANINGFUL: P2 ring buffer justified. (v2 drift math was time-stale.)")
        elif zero > len(lags) // 2:
            print("  → ALWAYS-CURRENT: P2 ring is overhead; drop it from roadmap.")
        else:
            print("  → MIXED: keep ring but gate P2 on flavor.")

    # ── item 2: 322 cadence
    print("\n## Item 2 — 322 (ClientMovementPredictionSync) emission & cadence")
    psy = [t for t, k, d in allrecs if k == "PSYNC"]
    cor = [t for t, k, d in allrecs if k in ("CORR", "CORRM")]
    if not psy:
        print("  no PSYNC records. Possibilities: (a) no corrections → client never emits,"
              " (b) client never emits at all (fixed timer/absent), (c) bus path issue.")
        print("  Hint: if corrections>0 and PSYNC==0 → item2b 30min A/B decides SILENT removal.")
    else:
        followed = 0
        dels = []
        for ct in cor:
            nxt = next((pt for pt in psy if ms_delta(ct, pt) > 0), None)
            if nxt is not None:
                followed += 1
                dels.append(ms_delta(ct, nxt))
        print(f"  322 count={len(psy)}; corrections followed-within-window: "
              f"{followed}/{len(cor)}; median delay={sorted(dels)[len(dels)//2] if dels else '-'}ms")
        print("  → if followed >= ~50%: answer-channel model holds, 322 hygiene MANDATORY.")

    # ── item 3: static vs evolving belief (idle phase drift across corrections)
    print("\n## Item 3 — belief static or evolving (uses idle phases: P0/P3)")
    for n, host, t0, t1, rs in phases:
        cs = corr_records(rs)
        if len(cs) < 2:
            continue
        drifts = []
        for (ta, da), (tb, db) in zip(cs, cs[1:]):
            pa = [f(x) for x in da.get("pos", "nan" * 3).split(",")]
            pb = [f(x) for x in db.get("pos", "nan" * 3).split(",")]
            if len(pa) == 3 and len(pb) == 3:
                drifts.append((sum((y - x) ** 2 for x, y in zip(pa, pb)) ** 0.5,
                               ms_delta(ta, tb)))
        if drifts:
            per_s = [d / (gap / 1000.0) for d, gap in drifts if gap > 300]
            d = sum(x for x, _ in drifts) / len(drifts)
            print(f"  phase {n}: consecutive-corr position drift mean={d:.3f} blocks "
                  f"(rates/s: {[f'{x:.3f}' for x in per_s[:6]]})")
    print("  → interpret on IDLE phases: |Δ|≈0 static (planner trivial) vs drifting (P3 needs gravity model).")

    # ── item 6: acceptance threshold (per-phase distance at correction time)
    print("\n## Item 6 — empirical acceptance threshold (distance corrPos vs client claim)")
    alld = []
    for n, host, t0, t1, rs in phases:
        ds = []
        for t, k, d in rs:
            if k != "CORR":
                continue
            pos = [f(x) for x in d.get("pos", "").split(",")]
            claim = [f(x) for x in d.get("claim", "").split(",")]
            if len(pos) == 3 and len(claim) == 3 and claim[0] == claim[0]:  # not nan
                # both frames are eye-level: corrPos(+1.62) vs AuthInput.Position (head/eye)
                ds.append(sum((a - b) ** 2 for a, b in zip(pos, claim)) ** 0.5 * -1 if False else
                          sum((a - b) ** 2 for a, b in zip(pos, claim)) ** 0.5)
        if ds:
            alld += ds
            h = hist(ds, [0.2, 0.35, 0.5, 0.65, 1.0, 2.0])
            print(f"  phase {n}: n={len(ds)} mean={sum(ds)/len(ds):.3f} hist={h}")
    if alld:
        lo = min(alld)
        print(f"  smallest distance that STILL drew a correction: {lo:.3f}")
        print("  → per-tick threshold ≈ that floor (NOT cumulative) if corrections track distance;")
        print("    if floor ≈ 0.5 (or 0.2–0.5 band): documented acceptance semantics confirmed.")

    # ── item 9: correction context classes
    print("\n## Item 9 — correction context: legit causes vs unexplained (self-inflicted)")
    cls = defaultdict(int)
    for t, k, d in allrecs:
        if k not in ("CORR", "CORRM"):
            continue
        ctx = [f(x) for x in d.get("ctx", "-1,-1,-1,-1,-1").split(",")] if k == "CORR" else [-1] * 5
        mt, tt, at, st, ut = (ctx + [-1] * 5)[:5]
        if 0 <= mt < 2000:
            cls["knockback/motion"] += 1
        elif 0 <= tt < 2000:
            cls["teleport"] += 1
        elif (0 <= st < 2000) or (0 <= ut < 2000):
            cls["attribute/data-latency"] += 1
        else:
            cls["UNEXPLAINED (likely self-inflicted)"] += 1
    for c, n in sorted(cls.items(), key=lambda x: -x[1]):
        print(f"  {n:>4} × {c}")
    if future := allrecs:  # noqa
        pass
    if cls.get("UNEXPLAINED (likely self-inflicted)", 0) == 0 and sum(cls.values()) > 0:
        print("  → all corrections had legit cause: classifier is dead weight, governor suffices.")
    elif cls:
        print("  → mixed causes confirmed: classifier earns its keep (P3 scope stands).")

    # ── item 10: cadence
    print("\n## Item 10 — correction cadence (gap histogram, ticks = 50ms)")
    gaps = [f(d.get("gap")) for t, k, d in allrecs if k in ("CORR", "CORRM") and d.get("gap", "-1") != "-1"]
    if gaps:
        gt = [g / 50.0 for g in gaps]
        h = hist(gt, [3, 5, 7, 10, 20, 40])
        print(f"  n={len(gaps)} gaps(ticks) hist={h}  median={sorted(gt)[len(gt)//2]:.1f}t")
        ge5 = sum(1 for g in gt if g >= 4.5)
        print(f"  ≥4.5t bucket share: {ge5}/{len(gt)}")
        if ge5 >= len(gt) * 0.8:
            print("  → documented 5-tick min-delay holds. §1 constants apply.")
        else:
            print("  → different scheduler: per-server calibration only; §1 numbers do NOT apply.")

    # ── item 12: MovePlayer modes
    print("\n## Item 12 — self MovePlayer modes observed (rubber-band channel)")
    modes = defaultdict(int)
    for t, k, d in allrecs:
        if k == "MP":
            modes[d.get("mode", "?")] += 1
    for m, n in sorted(modes.items(), key=lambda x: -x[1]):
        print(f"  {n:>4} × {m}")
    if modes:
        print("  → rubber-bands correlate with the mode seen during fly phases (check vs RESPAWN(1)).")

    # ── item 13: W/D sanity
    mots = [(t, d) for t, k, d in allrecs if k == "MOT"]
    if mots:
        print("\n## Item 13 — motion mapping live sanity (P5):")
        fwd = [f(d["my"]) for t, d in mots if f(d.get("my", "0")) > 0.3 and abs(f(d.get("mx", "0"))) < 0.3]
        strafe = [f(d["mx"]) for t, d in mots if f(d.get("mx", "0")) > 0.3 and abs(f(d.get("my", "0"))) < 0.3]
        print(f"  samples: forward-y={len(fwd)}, strafe-x={len(strafe)} "
              f"→ expect both >0 (y=fwd/x=strafe matches double-implementation concordance).")

    # ── item 8: ticked data packets during flight
    print("\n## Item 8 — self-targeted SetEntityData(39)/UpdateAttributes(29) with tick≠0")
    sads = [d for t, k, d in allrecs if k == "SAD" and d.get("tick", "0") != "0"]
    uats = [d for t, k, d in allrecs if k == "UAT" and d.get("tick", "0") != "0"]
    print(f"  SAD(tick≠0)={len(sads)}  UAT(tick≠0)={len(uats)}")
    print("  → non-zero counts during FLY phases = extra rewind anchors (P1 anchor scope);"
          " zeros = anchors stay {161, MovePlayer}.")

    if verbose:
        print("\n## (verbose) raw record counts")
        kinds = defaultdict(int)
        for _, k, _ in allrecs:
            kinds[k] += 1
        for k, n in sorted(kinds.items(), key=lambda x: -x[1]):
            print(f"  {n:>6} {k}")

    print("\nDone. Map hints to decisions with the matrix in docs/NO_LAGBACK_V3_VERIFICATION.md.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    verbose = "--all-phases" in sys.argv or "-v" in sys.argv
    path = sys.argv[1]
    report(segment(parse(path)), verbose)
