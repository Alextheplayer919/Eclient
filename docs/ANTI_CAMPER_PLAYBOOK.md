# Anti-Crystal-Camper Playbook (module research)

Situation: the enemy holes up in an obsidian box and crystal-camps you.
What our relay can *actually do* about it, mapped from Melody V2's module
arsenal (source: `melody-survey/`) onto Eclient's packet-layer line.
Verdict up front: campers die to **Trapper-with-opening + AnchorAura loop +
CrystalAura thresholds + AntiCrystal screen**. Rotation/movement-side tricks
(KillAura strafe etc.) don't crack a sealed hole — blocks do.

## 1. The hard counter-combo (all shippable on the relay line)

```
AutoTrap (Opening = TOWARD_ME) ──► AnchorAura (place→charge→detonate loop)
              ▲                            ▲
              └── same slot column ────────┘
CrystalAura (Min Place/Break Damage gates) + AntiCrystal (screen their crystals)
```

1. **AutoTrap (opening modes, this commit)** seals the camper's hole except
   one 2-block-tall "door" facing *you*. Ceiling stays closed. If they dig,
   the queue rebuilds and re-seals.
2. **AnchorAura** placement scans for the nearest free spot to the target —
   which is exactly the left-open door cell. It drops the anchor there,
   charges with glowstone, detonates in their face, and after the detonation
   the attempt drops in 300 ms so the **same spot gets re-anchored** on a
   fast cycle. Repeat until the hole is a crater.
3. **CrystalAura Min Place Damage / Max Self Damage / Min Break Damage**
   (this commit, Melody parity) stop the aura from wasting crystals on 0.x-
   damage taps and stop *you* from face-tanking your own explosions while
   standing on the hole.
4. **AntiCrystal** (breaker screen) keeps *their* crystals from popping while
   you're up close placing anchors — the camper's only counter-play is speed,
   which the 80 ms anchor cooldown out-cycles.

## 2. What Melody has, and what we took from it

| Melody module | Purpose anti-camper | Status in Eclient |
|---|---|---|
| Autocrystal (damage gates, ID predict, multi-place) | their own placing loop | **Surpassed already** (Eclient has exposure-raycast sim + pending verification + blacklist; now also the damage gates) |
| Anticrystal (20 lines: break crystals near you) | screen | exists (AntiCrystal) — upgrade path below |
| Surround (212 lines, offset list, burrow variants) | trap | **AutoTrap** (opening modes this commit) |
| AutoMine / PacketMine | dig the camper's wall | tier B: needs world-block axis + simulate block-break progress; doable via `PlayerActionPacket` START/STOP on the exposed wall |
| AntiAnvil (52 lines) | counter anvil-drop escapes | trivial variant: place block above own head when anvil entity | PacketMine is Melody's version of "mine their obsidian" |
| Disabler / NoPacket | server-specific | parked (server-dependent) |

## 3. Recommended server-by-server configs

- **Low-AC server (most community realms):** AutoTrap OBSIDIAN + Opening
  TOWARD_ME, AnchorAura cooldown 80–120 ms, Max Simultaneous 3 — the loop is
  brutal and fast. CrystalAura Min Place 4 / Max Self 8 / Min Break 1.
- **Stricter AC:** raise packetGapMs (100–150), Max Simultaneous 1–2, and
  prefer crystal-only pressure (CrystalAura with gates) since rapid
  transaction bursts are what trips ACs.
- **If they counter-dig the hole:** Opening NONE for full seal, KillAura
  + Target Lock to hold them inside your reach until the seal completes,
  then AnchorAura on the dug opening (it auto-finds the newest free cell —
  no config change needed).

## 4. Deferred ideas (need more protocol or native state)

- **AutoMine-into-hole** (PlayerActionPacket block-break sequencing on the
  opening) — real path for *cracking sealed full-surrounds* without anchors;
  tier B, next candidate after field test.
- **Silent pressure: place anchor INSIDE their sealed box** by predicting
  their dig-out and pre-emptively bombing the cell they retreat into.
- Wall-check placement for crystals (seenPercent) once chunk cache covers
  more states — today exposure sampling covers the common cases.
