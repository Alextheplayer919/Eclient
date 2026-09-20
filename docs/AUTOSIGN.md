## AutoSign — `module/world/AutoSign.kt`

Utility module: auto-writes the player's name and the date on signs they
place — **only on lines left empty, only where the player didn't write**.

### Wire ground truth (researched)

Bedrock sign editing:

1. Player places a sign → server sends **`OpenSignPacket`** (position +
   `FrontSide` bool — both sides editable since 1.19.80) to open the editor.
2. The editor result travels client→server as **`BlockEntityDataPacket`**
   holding the sign's block-entity NBT.

Sign NBT shape (current protocol):

```
Sign
├─ FrontText : Compound { "Text": "<4 lines, newline-joined>", ... }
└─ BackText  : Compound { "Text": "...", ... }
```

Vanilla protocol: no server-side ownership check — the
`BlockEntityData` blob *is* the accepted text (proxy rewrite is the correct
insertion point; OpenSign is S2C-only and drives purely the client UI).

### What the module does

Intercepts **C2S** `BlockEntityDataPacket` with `id == "Sign"`:

- splits `Text` into 4 lines; writes the template values **only into blank
  lines** (`"nothing else, unless the player modifies"` — user requirement),
- default layout: line 2 = gamertag (`EntityTracker.getSelfName()`), line 3 =
  date (`SimpleDateFormat`, default `dd MMM yyyy`), lines 1 & 4 untouched,
- settings: `Name line (1..4)`, `Date line (1..4)`, `Also fill back side`
  (default off), `Date format` (editable),
- handles **both** the modern `FrontText`/`BackText` form and the legacy
  top-level `Text` form, so older servers/versions still work,
- uses `PacketEvent.cancelAndReplace(packet)` — every other module keeps
  seeing and can further modify the packet afterwards (bus semantics),
- no internal state; nothing to persist; zero packets injected (it only
  rewrites one packet the client was already sending).

### Known limits

- If the player's name is blank (auth hiccup) the rewrite is skipped.
- Date is local time of the device (intended — "the date of writing").
- Waxed signs: server rejects edits on its own; the module does not attempt
  to rewrite waxed sign saves (they'd just be rejected).
