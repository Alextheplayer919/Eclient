// Runtime loader for the GENERATED Java→Bedrock state table. The data itself
// lives in app/src/main/assets/schem/blocks_statemap.nbt (gzipped NBT, ~27 KB)
// produced by tools/gen_block_statemap.py from GeyserMC mappings feat/26.1
// × minecraft-data pc/26.1. Do not hand-edit the asset; regenerate it.
//
// Why an asset and not source: a 600 KB mapOf literal made the Kotlin
// compiler crawl (debug CI 19 min+ → normal ~5 min after this split).

package com.rubidiumclient.core.schem

import com.rubidiumclient.RubidiumClientApp
import com.rubidiumclient.utils.DiagLog
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NBTInputStream
import org.cloudburstmc.nbt.NbtUtils

/**
 * Java → Bedrock block STATE translation (companion to BlockIdMap, ids only).
 * Public shape mirrors the original generated object — see docs/AUTOBUILD §2b.
 */
object BlockStateMap {

    private const val TAG   = "BlockStateMap"
    private const val ASSET = "schem/blocks_statemap.nbt"

    /** One separable rule: bedrock state key + java value → bedrock value table. */
    class Rule(val bedrockKey: String, val values: Map<String, Any>)

    @Volatile private var loaded    = false
    @Volatile var available: Boolean = false
        private set

    private var namesBacking   : Map<String, String>                            = emptyMap()
    private var rulesBacking   : Map<String, Map<String, Rule>>                 = emptyMap()
    private var complexBacking : Map<String, Pair<String?, Map<String, Any>>>   = emptyMap()

    /** java block name → bedrock identifier (uniform overrides only). */
    val NAME_OVERRIDES: Map<String, String>
        get() { ensureLoaded(); return namesBacking }

    /** PROP_RULES[javaBlock][javaProp] = rule covering that property for ALL variants. */
    val PROP_RULES: Map<String, Map<String, Rule>>
        get() { ensureLoaded(); return rulesBacking }

    /** Keyed full tables for non-separable blocks. Key: "minecraft:id[k=v,...]". */
    val COMPLEX_STATES: Map<String, Pair<String?, Map<String, Any>>>
        get() { ensureLoaded(); return complexBacking }

    // ── lookups ───────────────────────────────────────────────────────────────

    /** Bedrock block id for a java block name (identity default). */
    fun bedrockIdOf(javaName: String): String = NAME_OVERRIDES[javaName] ?: javaName

    /** Bedrock-state constraints for a java state (emitted keys only; absence = unconstrained). */
    fun toBedrockStates(javaName: String, props: Map<String, String>): Map<String, Any> {
        ensureLoaded()
        if (props.isNotEmpty()) {
            val key = javaName + "[" + props.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=$v" } + "]"
            complexBacking[key]?.let { return it.second }
        }
        val rs = rulesBacking[javaName] ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((p, v) in props) {
            val r = rs[p] ?: continue
            r.values[v]?.let { out[r.bedrockKey] = it }
        }
        return out
    }

    /** Bedrock id accounting for complex (state-dependent) overrides too. */
    fun bedrockIdOf(javaName: String, props: Map<String, String>): String {
        ensureLoaded()
        if (props.isNotEmpty()) {
            val key = javaName + "[" + props.entries.sortedBy { it.key }.joinToString(",") { (k, v) -> "$k=$v" } + "]"
            complexBacking[key]?.first?.let { return it }
        }
        return namesBacking[javaName] ?: javaName
    }

    // ── asset loading ─────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val app = RubidiumClientApp.instance
            app.assets.open(ASSET).use { ins ->
                val tag = NbtUtils.createGZIPReader(ins).use(NBTInputStream::readTag)
                val root = tag as? NbtMap ?: throw IllegalStateException("statemap root is not a compound")

                namesBacking = LinkedHashMap<String, String>().also { m ->
                    root.getCompound("names")?.let { c -> for (k in c.keys) m[k] = c.getString(k) ?: continue }
                }
                rulesBacking = LinkedHashMap<String, Map<String, Rule>>().also { m ->
                    root.getCompound("rules")?.let { rc ->
                        for (blk in rc.keys) {
                            val bc = rc.getCompound(blk) ?: continue
                            val inner = LinkedHashMap<String, Rule>()
                            for (p in bc.keys) {
                                val pc = bc.getCompound(p) ?: continue
                                val bkey = pc.getString("key") ?: continue
                                val vals = LinkedHashMap<String, Any>()
                                pc.getCompound("vals")?.let { vc -> for (jv in vc.keys) vc[jv]?.let { vals[jv] = it } }
                                inner[p] = Rule(bkey, vals)
                            }
                            m[blk] = inner
                        }
                    }
                }
                complexBacking = LinkedHashMap<String, Pair<String?, Map<String, Any>>>().also { m ->
                    root.getCompound("complex")?.let { cc ->
                        for (key in cc.keys) {
                            val c = cc.getCompound(key) ?: continue
                            val id = c.getString("@id")
                            val st = LinkedHashMap<String, Any>()
                            for (k in c.keys) if (k != "@id") c[k]?.let { st[k] = it }
                            m[key] = id to st
                        }
                    }
                }
            }
            available = true
            DiagLog.log(TAG, "loaded state map: overrides=${namesBacking.size} rules=${rulesBacking.size} complex=${complexBacking.size}")
        } catch (e: Exception) {
            available = false
            DiagLog.log(TAG, "state map asset failed to load ($ASSET): ${e.message} — identity/empty fallback")
        }
    }
}
