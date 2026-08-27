package com.rubidiumclient.utils

object MiningUtil {

    private const val TICKS_PER_SECOND = 20

    enum class ToolTier(val speed: Float) {
        NONE(1f), WOOD(2f), GOLD(12f), STONE(4f), IRON(6f), DIAMOND(8f), NETHERITE(9f)
    }

    private val HARDNESS: Map<String, Float> = buildMap {
        put("stone", 1.5f); put("cobblestone", 2.0f); put("mossy_cobblestone", 2.0f)
        put("deepslate", 3.0f); put("cobbled_deepslate", 3.5f); put("polished_deepslate", 3.5f)
        put("netherrack", 0.4f); put("obsidian", 50f); put("crying_obsidian", 50f)
        put("dirt", 0.5f); put("grass_block", 0.6f); put("podzol", 0.5f); put("mycelium", 0.6f)
        put("sand", 0.5f); put("red_sand", 0.5f); put("gravel", 0.6f)
        put("oak_log", 2.0f); put("oak_planks", 2.0f); put("oak_leaves", 0.2f)
        put("glass", 0.3f); put("sandstone", 0.8f); put("bedrock", -1f)
        put("coal_ore", 3.0f); put("iron_ore", 3.0f); put("gold_ore", 3.0f)
        put("diamond_ore", 3.0f); put("emerald_ore", 3.0f); put("lapis_ore", 3.0f)
        put("redstone_ore", 3.0f); put("copper_ore", 3.0f); put("nether_quartz_ore", 3.0f)
        put("deepslate_coal_ore", 4.5f); put("deepslate_iron_ore", 4.5f); put("deepslate_gold_ore", 4.5f)
        put("deepslate_diamond_ore", 4.5f); put("deepslate_emerald_ore", 4.5f); put("deepslate_lapis_ore", 4.5f)
        put("deepslate_redstone_ore", 4.5f); put("deepslate_copper_ore", 4.5f)
        put("ancient_debris", 30f)
        put("coal_block", 5f); put("iron_block", 5f); put("gold_block", 3f)
        put("diamond_block", 5f); put("emerald_block", 5f); put("redstone_block", 5f)
        put("lapis_block", 3f); put("copper_block", 3f)
        put("amethyst_block", 1.5f); put("budding_amethyst", 1.5f)
        put("blackstone", 1.5f); put("basalt", 1.25f); put("tuff", 1.5f); put("calcite", 0.75f)
        put("water", -1f); put("lava", -1f); put("air", 0f)
    }

    private val REQUIRES_PICKAXE_HINTS = arrayOf(
        "stone", "ore", "deepslate", "obsidian", "netherrack", "blackstone",
        "concrete", "terracotta", "brick", "basalt", "tuff", "calcite",
        "amethyst", "quartz", "copper_block", "iron_block", "gold_block",
        "diamond_block", "emerald_block", "coal_block", "redstone_block",
        "lapis_block", "anvil", "furnace", "rail"
    )

    private fun shortName(identifier: String): String =
        identifier.removePrefix("minecraft:")

    fun hardnessOf(identifier: String): Float {
        val n = shortName(identifier)
        HARDNESS[n]?.let { return it }
        for ((key, value) in HARDNESS) if (n.endsWith(key)) return value
        return 1.5f
    }

    fun requiresPickaxe(identifier: String): Boolean {
        val n = shortName(identifier)
        return REQUIRES_PICKAXE_HINTS.any { n.contains(it) }
    }

    fun toolTierOf(itemIdentifier: String?): ToolTier {
        if (itemIdentifier == null) return ToolTier.NONE
        val n = shortName(itemIdentifier)
        if (!n.endsWith("_pickaxe")) return ToolTier.NONE
        return when {
            n.startsWith("wooden") || n.startsWith("wood")   -> ToolTier.WOOD
            n.startsWith("golden") || n.startsWith("gold")   -> ToolTier.GOLD
            n.startsWith("stone")                            -> ToolTier.STONE
            n.startsWith("iron")                             -> ToolTier.IRON
            n.startsWith("diamond")                          -> ToolTier.DIAMOND
            n.startsWith("netherite")                        -> ToolTier.NETHERITE
            else                                             -> ToolTier.NONE
        }
    }

    /**
     * Efficiency büyüsü şu an okunmuyor (ItemData'dan enchantment NBT parse
     * gerektiriyor, bu proje için doğrulanmadı) — bu yüzden efficiencyLevel
     * her zaman 0 varsayılır. Sonuç, gerçek kırma süresinden biraz YAVAŞ
     * (daha güvenli/legit görünümlü) çıkar, hızlı değil.
     */
    fun breakTimeTicks(
        blockIdentifier: String,
        heldItemIdentifier: String?,
        efficiencyLevel: Int = 0,
        inWater: Boolean = false,
        onGround: Boolean = true
    ): Int {
        val hardness = hardnessOf(blockIdentifier)
        if (hardness < 0f) return -1
        if (hardness == 0f) return 1

        val tier = toolTierOf(heldItemIdentifier)
        val needsPickaxe = requiresPickaxe(blockIdentifier)

        var speed = if (needsPickaxe) tier.speed else ToolTier.NONE.speed
        if (efficiencyLevel > 0) speed += (efficiencyLevel * efficiencyLevel + 1)

        if (inWater) speed /= 5f
        if (!onGround) speed /= 5f

        var damage = speed / hardness
        damage /= if (needsPickaxe && tier != ToolTier.NONE) 30f else 100f

        if (damage > 1f) return 1

        val seconds = 1f / damage
        val ticks = Math.ceil((seconds * TICKS_PER_SECOND).toDouble()).toInt()
        return ticks.coerceAtLeast(1)
    }
}
