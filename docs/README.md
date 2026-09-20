# Client documentation index

Entry point for the docs/ folder. Everything here documents **what the code
actually does today** — regenerate/update the reference docs whenever modules
or protocol usage change.

## Reference (generated from code, 2026-09-19)

| Doc | Covers |
|---|---|
| **MODULES.md** | All 78 registered module instances: purpose, settings (defaults+ranges), packets read/created/sent, key functions. Includes the framework contract (`BaseModule`/`ModuleManager`), filename↔class-name drift table, and the unregistered-but-present files. |
| **PROTOCOL.md** | Every CloudburstMC class used in the tree → consumer files, blast-radius ranking, the 15 invariants the code physically relies on, version-gate watchpoints. Start here when a protocol/codec/NBT dependency update breaks or changes behaviour. |
| **CORE-ARCHITECTURE.md** | Relay topology (`RubidiumRelay`/`RubidiumRelaySession`, `serverBound`/`clientBound`), the `PacketEventBus` (cancel/replace semantics + threading), trackers (`EntityTracker` incl. the eye/feet Y-frame flag, `WorldBlockTracker`, `CollisionGuard`, `MovementCompliance`), god-utils (`PlacementUtil`, `InventoryUtil`, …), and one-liners for auth/config/session/ui. |

## Feature-deep docs (hand-maintained)

| Doc | Covers |
|---|---|
| AUTOBUILD.md | Java→Bedrock schematic auto-build: converter, planner, v1 printer (field-validated), the verified protocol table (CLICK_BLOCK=0, auth-input flags 34/35/36, ITx-legitimacy), phase plan |
| SCHEMATICA.md | Ghost renderer + hand-encoded DebugDrawer packet 328 + `.litematic` parsing |
| NO_LAGBACK_V3.md / NO_LAGBACK_V3_VERIFICATION.md | Anti-lagback governor design + FlightProbe verification procedure |
| CHAT-COMMANDS.md | '.'-prefix chat commands: exact-match dispatch, local-only S2C replies (leak-proof .coords), registry reference |
| FRIENDS.md | Global friend/enemy lists + the sendToServer friend-attack choke (no module can bypass) + /w notifications |
| AUTOSIGN.md | AutoSign: sign wire flow (OpenSign + BlockEntityDataPacket) and the empty-lines-only rewrite |
| AUTOTORCH.md | AutoTorch: researched Bedrock spawn rules, no-light-on-the-wire finding, client-side light flood |
| MELODY_MODULES.md | KillAura and the Melody combat module family |
| ANTI_CAMPER_PLAYBOOK.md | ACA / anti-camper playbooks |

## Update rules (so docs never drift)

1. New/changed module → update the matching section in MODULES.md the same commit.
2. New packet class or protocol field assumption → add/flag it in PROTOCOL.md §2/§3.
3. Changed invariants (packet shapes, frame semantics, wire constants) → update PROTOCOL.md §3 *and* the AUTOBUILD.md verification table if placement-related.
4. Deep docs (feature files) own their design narrative; reference docs own the inventory. Don't duplicate: link instead.
5. `app/src/main/assets/schem/blocks_statemap.nbt` is GENERATED (`tools/gen_block_statemap.py`) — never hand-edit the asset; change the generator or its pinned sources instead. The Kotlin loader (`core/schem/BlockStateMap.kt`) IS hand-edited source.
