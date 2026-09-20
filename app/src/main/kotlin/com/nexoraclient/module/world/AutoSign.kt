package com.rubidiumclient.module.world

import com.rubidiumclient.core.proxy.EntityTracker
import com.rubidiumclient.events.PacketEvent
import com.rubidiumclient.module.BaseModule
import com.rubidiumclient.module.ModuleCategory
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.protocol.bedrock.packet.BlockEntityDataPacket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AutoSign — fills sign text automatically when the player saves a sign.
 *
 * Flow (docs/AUTOSIGN.md): server opens the sign editor via OpenSign, the
 * player edits (or closes instantly), the client sends the resulting text as
 * a C2S BlockEntityDataPacket ("id":"Sign", FrontText/BackText compounds,
 * "Text" = 4 newline-joined lines). We rewrite THAT packet on its way out:
 *
 *   - the two configured template lines are written ONLY when the player left
 *     them empty ("writes name and date below, nothing else, unless the
 *     player modifies") — any hand-typed line wins,
 *   - default layout: line 2 = gamertag, line 3 = date, lines 1 & 4 untouched,
 *   - both the modern (FrontText/BackText) and legacy top-level "Text" forms
 *     are handled, so older server versions auto-work too.
 */
class AutoSign : BaseModule(
    name        = "AutoSign",
    category    = ModuleCategory.WORLD,
    description = "Writes your name + date on sign lines you left empty (default: line 2 = name, line 3 = date)"
) {
    private val nameLine = int("Name line (1-4)", 2, 1, 4)
    private val dateLine = int("Date line (1-4)", 3, 1, 4)
    private val includeBack = bool("Also fill back side", false)
    private val dateFormat  = string("Date format", "dd MMM yyyy")

    override fun onPacket(event: PacketEvent) {
        if (!isEnabled) return
        if (!event.isClientToServer) return
        val p = event.packet as? BlockEntityDataPacket ?: return
        val tag = p.data ?: return
        val id = tag.getString("id") ?: return
        // all dustless/hanging variants share the "Sign" block-entity id
        if (!id.endsWith("Sign") && id != "Sign") return

        val name = runCatching { EntityTracker.getSelfName() }.getOrDefault("")
        if (name.isBlank()) return
        val date = runCatching {
            SimpleDateFormat(dateFormat.value, Locale.getDefault()).format(Date())
        }.getOrDefault(SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()))

        val nl = (nameLine.value - 1).coerceIn(0, 3)
        val dl = (dateLine.value - 1).coerceIn(0, 3)

        var changed = false
        val builder = NbtMap.builder()

        for (key in tag.keys) {
            val value = tag[key]
            if (key == "FrontText" || (key == "BackText" && includeBack.value)) {
                (value as? NbtMap)?.let { face ->
                    val lines = (face.getString("Text") ?: "").split("\n").toMutableList()
                    while (lines.size < 4) lines += ""
                    val (newText, did) = applyTemplate(lines, nl, dl, name, date)
                    if (did) {
                        changed = true
                        val fb = NbtMap.builder()
                        for (fk in face.keys) fb.put(fk, face[fk]!!)
                        fb.putString("Text", newText)
                        builder.put(key, fb.build())
                        return@let
                    }
                    builder.put(key, face)
                } ?: builder.put(key, value)
            } else if (key == "Text" && tag.getCompound("FrontText") == null) {
                // legacy form
                val lines = (value as? String ?: "").split("\n").toMutableList()
                while (lines.size < 4) lines += ""
                val (newText, did) = applyTemplate(lines, nl, dl, name, date)
                if (did) { changed = true; builder.putString("Text", newText) } else builder.putString("Text", value as? String ?: "")
            } else {
                builder.put(key, value)
            }
        }

        if (!changed) return
        val rewritten = BlockEntityDataPacket().apply {
            blockPosition = p.blockPosition
            data          = builder.build()
        }
        event.cancelAndReplace(rewritten)
    }

    /** Fill [nameLine]/[dateLine] ONLY when the player left them empty. Returns (text, changed). */
    private fun applyTemplate(
        lines: MutableList<String>, nameLine: Int, dateLine: Int, name: String, date: String
    ): Pair<String, Boolean> {
        var changed = false
        if (lines[nameLine].isBlank()) { lines[nameLine] = name; changed = true }
        if (dateLine != nameLine && lines[dateLine].isBlank()) { lines[dateLine] = date; changed = true }
        return lines.take(4).joinToString("\n") to changed
    }
}
