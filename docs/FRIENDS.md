## Friends / Enemies — `core/social/Friends.kt`

### Scope & storage

- **Global lists** (user decision), matched by gamertag case-insensitively.
  Persisted to `<filesDir>/friends.json` (friends, enemies, notify flag,
  message texts), loaded at `ChatCommands.init()`.
- Friend and enemy sets are mutually exclusive — adding one evicts the other.

### The absolute attack guard (why no module can bypass it)

Module-injected combat packets **do not cross the PacketEventBus**: ported
combat modules call `session.serverBound(...)`, which is
`RubidiumRelaySession.sendToServer()` directly. Any guard living on the bus
would therefore silently miss exactly the attacks it must block.

`FriendGuard.isAttackOnFriend()` is instead invoked **inside
`RubidiumRelaySession.sendToServer()`** — the single funnel every outbound
packet (real client inputs, relay relaying, module injections) flows through
before the wire. It inspects:

    InventoryTransactionPacket, transactionType == ITEM_USE_ON_ENTITY,
    actionType == 1 (ATTACK)

resolves `runtimeEntityId` via `EntityTracker` (playerNames/entity name), and
if that name is a friend: **the packet is dropped before touching the
socket**. A local, throttled (2 s per target) notice explains the block.
Exceptions in the guard fail **open** (attack passes) — a crashy guard is
worse than a permissive one, and DiagLog records it.

To bypass this, a module would need to open its own socket — no public API
path exists for that.

- Interact/use variants (`actionType 2`, `InteractPacket`) are **not**
  combat and pass through untouched (right-clicking a friend's frame, etc.).

### Whisper notifications

`.friend add/remove` optionally sends `/w <player> <msg>` via
`CommandRequestPacket` (the server's real command path — server confirmed to
support `/w`). Behaviour matches the spec:

- Notify **only on state change** (repeat adds / removes of absent names are
  silent except the local reply).
- Input matched case-insensitively; the whisper uses the *canonical* online
  name from the player-list cache.
- Not online → skipped, local notice, never queued for later.
- Toggle: `.friend notify on|off` (persisted, default on).
- Texts: `.friend msg add <text>` / `.friend msg remove <text>` (persisted;
  defaults "Added you to my friend list" / "Removed you from my friend list").
- Rate limit: a daemon pump drains the queue at one command per 350 ms, so
  batched adds can't burst (concurrent producers handled with a double-check
  before releasing the pump lock).

### Field diagnostics (v3.1)

Whisper paths log every step to DiagLog (`Downloads/baba.txt`, tag
`FriendLibrary`): `whisper queued`, `whisper sent`, `whisper send exception`,
`whisper skipped: '<name>' not resolvable online`. Online resolution searches
PlayerListPacket first (authoritative casing, covers players the server never
sent AddPlayerPacket for) with entity records as fallback
(`EntityTracker.onlineNameOf`). The command request now carries a real UUID
`requestId` (some PMMP forks reject empty request ids).

### Later milestone (decision recorded)

KillAura targeting order — enemies first (ignoring distance) → neutrals by
distance → friends as configurable last resort — will consume these lists in
a dedicated combat-logic milestone.
