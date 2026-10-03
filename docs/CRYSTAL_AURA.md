# CrystalAura — placement flow and packet notes

CrystalAura is a Bedrock packet-level module. It does not authoritatively place
blocks itself: it asks the server to perform the use, then waits for the server
world update before treating an obsidian base as real.

## Bedrock placement contract

For a block/item interaction, the code uses `InventoryTransactionPacket` with
`transactionType = ITEM_USE` and `actionType = 0` (click block). The packet must
identify the **existing block that is clicked**, its face, the selected hotbar
slot and actual held item, the player's interaction position, and a click point
relative to that block. The click point is in the block's local 0..1 space.
The inventory action list records the predicted held-stack decrement. The
selected-slot `MobEquipmentPacket` also needs the real item, not an AIR stack.
These fields are assembled centrally in `PlacementUtil.sendPlacementUseRaw`;
CrystalAura passes a real support block for obsidian and the obsidian/bedrock
base with its upper face for the crystal.

The server's world state remains authoritative. An outbound transaction being
queued is **not** proof of placement; CrystalAura waits for a server block
update confirming obsidian before sending the crystal use. It then waits for a
crystal entity spawn to confirm crystal placement.

End crystals have stricter placement rules than ordinary blocks: their base
must be obsidian or bedrock, the two blocks above must be clear/replaceable,
and another entity must not occupy the placement area. CrystalAura checks the
base and two-block column from `WorldBlockTracker` before use; the server remains
the final authority for entity collision and can still reject a use. Unknown
world cells are not treated as air, because doing so can produce ghost
placements.

## Fixes in this pass

- A rejected/unacknowledged auto-obsidian use previously left the target-cell
  attempt key latched after its pending request timed out. With a stationary
  target, every later tick hit that key and returned, so a transient rejection
  permanently stopped the setup sequence. It now clears the in-flight key and
  retries after a short backoff. A server-confirmed base is tracked separately
  so a failed damage gate does not cause the aura to consume more obsidian in
  duplicate positions; if that base is later removed, it can build again.
- The placement search now checks target-foot and local-player-foot height tiers,
  then tries a two-block horizontal ring. It still requires server-known air, a
  real clickable support, clear crystal space, reach, and no player occupying
  the obsidian destination.
- `WorldBlockTracker.hasAnyTerrainData()` now counts authoritative single-block
  overrides as data, not only decoded chunk sections. Reset also clears cached
  runtime-ID-to-name lookups so a reconnect/dimension transition cannot reuse
  stale block identities.

## Safety and debugging

Auto-base placement and crystal use are separate decisions. Even with a base in
place, the existing `Min Place Damage`, `Max Self Damage`, and `Suicide` settings
can intentionally prevent crystal use when the simulated damage is too low or
self-damage is too high. `VerboseLog` writes candidate/rejection summaries to
`baba.txt`; `Log` also enables the module's in-game status messages. A packet
send being successful only means the request was sent—the server may still
reject it for reach, collision, inventory, permissions, or server rules.

## Research sources

- Mojang's Bedrock protocol packet reference, `InventoryTransactionPacket` and
  its item-use fields: <https://mojang.github.io/bedrock-protocol-docs/latest/packets/inventory-transaction-packet/>
- PocketMine's `UseItemTransactionData` API, including `ACTION_CLICK_BLOCK` and
  the block position/face/item/player/click-position fields:
  <https://apidoc.pmmp.io/dd/d91/classpocketmine_1_1network_1_1mcpe_1_1protocol_1_1types_1_1inventory_1_1_use_item_transaction_data.html>
- Minecraft Wiki, End Crystal placement requirements:
  <https://minecraft.wiki/w/End_Crystal>
- Bedrock protocol inventory note: `ItemStackRequest` is used for inventory
  moves, while `InventoryTransaction` remains the gameplay path for block use:
  <https://wiki.vg/Bedrock_Protocol>
