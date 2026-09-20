## Chat command system — `core/commands/ChatCommands.kt`

`.`-prefixed commands executed inside the game chat, with responses that are
**local-only by construction**.

### The three hard guarantees

1. **Nothing leaks publicly.** Command replies are `TextPacket(Type.SYSTEM)`
   fed through `session.clientBound()` — they are fabricated server-to-client
   packets injected into the local chat feed. A reply never exists as a
   C2S byte, so e.g. `.coords` output physically cannot appear in public
   chat. The command line itself is swallowed by `PacketEvent.cancel()`
   before the relay forwards anything to the server.
2. **No prefix-line matching.** A message starting with `.` is *not*
   automatically treated as a command attempt. Dispatch requires the token
   stream to **exactly** match a registered command name (case-insensitive,
   longest match up to 3 tokens: `.friend add`).
   - `.` alone, `. _ .`-style text, `.hello`, `.coordsss` → **pass through
     unchanged** as normal public chat, by design (user requirement).
   - Any exception during parsing/dispatch fails **open**: the packet goes
     through as-if untouched.
3. **Real client lines only.** Only `TextPacket` with `Type.CHAT` is
   considered (translation/system/popup types are ignored). Injection point:
   PacketEventBus priority `-900` (runs before all modules, after
   FriendGuard). Module-injected chat (ChatSpammer etc.) goes through
   `serverBound()` and never re-enters dispatch, so no echo loops.

### Registry entries (self-documenting via `.help`)

`.help [cmd]` · `.coords [copy]` · `.toggle <module>` · `.panic` · `.list` ·
`.config list|current|save|load <name>` · `.friend add|remove|list|notify|msg` ·
`.enemy add|remove|list` · `.schem load|toggle|layer|nudge` ·
`.build start|stop|status`

- `.toggle` resolves exact then unique-substring (case-insensitive);
  ambiguous matches list the candidates and change nothing.
- `.panic` calls `ModuleManager.disableAll()` synchronously on the dispatch
  thread — enabled modules stop mid-loop, no module can act on stale state.
- `.config *` runs on Dispatchers.IO and calls the exact `Config` suspend
  functions the GUI uses; `current` tracks the last name touched via chat
  (best effort, per process).
- `.build status` reads AutoBuilder's counters (placed/total, pending,
  in-flight, failed, breaker) through the Schematica command surface.
- Each `Def` carries syntax/description/example, so `.help` is always in
  sync — no parallel documentation table to maintain. (This file is exactly
  why: count of Defs ≙ printed count in the diag log.)

### Clipboard note

`.coords copy` posts to the main-thread `ClipboardManager`. Android 13+
shows the system clipboard toast on every write — that's expected and
intentional (explicit user action only, per design). On devices where the
toast doesn't appear, DiagLog (`Downloads/baba.txt`) records
`clipboard write OK` / `clipboard write FAILED: <reason>` (OEM clipboards vary
on Service-origin writes).

### Bus-clear survival (regression fix)

`PacketEventBus.clear()` (Dashboard/SessionManager stop) wipes every
listener — and a Kotlin `object` never re-initializes. `ChatCommands`
re-arms per connection via `ModuleManager.registerToSession()` (the hook
ConnectionManager already calls on every new relay session); enabled modules
are re-registered there too (the old no-op hook is now the re-registration
contract). A successful dispatch also writes a DiagLog line
(`Downloads/baba.txt`, tag `ChatCommands`) so field tests can prove the
intercept fired.

### Out of scope (deliberate — user decision)

`.bind` (no keys on touchscreen), `.set` (settings stay menu-owned; commands
own actions), `.reload` (session/codec reload semantics are hairy; configs
already cover state).

### KillAura friend/enemy ranking

List storage + commands landed now; the targeting rule (enemies first →
neutrals by distance → friends last-resort) is its own later milestone —
combat logic gets a dedicated review pass.
