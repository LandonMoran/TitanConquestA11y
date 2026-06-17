package com.titanconquest.a11y.model

/**
 * Represents the authenticated session with titanconquest.com
 */
data class Session(
    val username: String,
    val cookies: Map<String, String>
)

/**
 * Hero stats shown on the main game screen
 */
data class HeroStats(
    val name: String,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val power: Int,
    val drachma: Long,
    val xp: Long,
    val xpToNextLevel: Long,
    val location: String,
    val locationPoints: Int
) {
    val hpPercent: Float get() = if (maxHp > 0) hp.toFloat() / maxHp else 0f
    val xpPercent: Float get() = if (xpToNextLevel > 0) xp.toFloat() / xpToNextLevel else 0f

    /** Accessibility-friendly summary read aloud by TalkBack */
    fun toContentDescription(): String =
        "$name, Level $level. HP: $hp of $maxHp. " +
        "Power: $power. Drachma: $drachma. " +
        "Location: $location."
}

/**
 * An enemy encountered during patrol
 */
data class Enemy(
    val id: String,
    val name: String,
    val tier: Int,           // 1 = normal, 2 = II, 3 = III, etc.
    val hp: Int,
    val maxHp: Int,
    val isAvenging: Boolean  // true if shown red (enemy killed a player)
) {
    val displayName: String get() = if (tier > 1) "$name ${"I".repeat(tier)}" else name

    fun toContentDescription(): String {
        val base = "$displayName. HP: $hp of $maxHp."
        return if (isAvenging) "$base Avenging opportunity — double rewards!" else base
    }
}

/**
 * Result of a battle action (patrol strike, running, taking cover)
 */
data class BattleResult(
    val action: BattleAction,
    val heroHpAfter: Int,
    val enemyHpAfter: Int,
    val damageDealt: Int,
    val damageTaken: Int,
    val xpGained: Long,
    val drachmaGained: Long,
    val lootDropped: LootDrop?,
    val enemyDefeated: Boolean,
    val playerRan: Boolean
) {
    /** Full spoken summary for TalkBack announcement */
    fun toAnnouncement(): String = buildString {
        when (action) {
            BattleAction.STRIKE -> {
                append("You struck for $damageDealt damage.")
                if (damageTaken > 0) append(" Enemy hit you for $damageTaken.")
                if (enemyDefeated) {
                    append(" Enemy defeated!")
                    append(" Gained $xpGained XP and $drachmaGained Drachma.")
                    lootDropped?.let { append(" Loot: ${it.description}.") }
                } else {
                    append(" Enemy HP: $enemyHpAfter remaining.")
                }
            }
            BattleAction.RUN -> append("You ran from battle. HP restored.")
            BattleAction.TAKE_COVER -> append("You took cover. HP partially restored.")
            BattleAction.USE_SUPER -> append("Super ability used!")
        }
    }
}

enum class BattleAction { STRIKE, RUN, TAKE_COVER, USE_SUPER }

/**
 * Loot dropped from a defeated enemy
 */
data class LootDrop(
    val memoryType: MemoryType,
    val itemName: String
) {
    val description: String get() = "${memoryType.label} memory — $itemName"
}

enum class MemoryType(val label: String) {
    WHITE("Common"),
    GREEN("Uncommon"),
    BLUE("Rare"),
    PURPLE("Legendary"),
    RED("Triumphant")
}

/**
 * A location the hero can travel to
 */
data class Location(
    val id: String,
    val name: String,
    val isUnlocked: Boolean,
    val locationPointsRequired: Int,
    val currentEnemyCount: Int
) {
    fun toContentDescription(): String {
        return if (isUnlocked) {
            "$name. $currentEnemyCount enemies present."
        } else {
            "$name. Locked. Requires $locationPointsRequired location points."
        }
    }
}

/**
 * A chat message from the global or clan chat
 */
data class ChatMessage(
    val sender: String,
    val message: String,
    val timestamp: String,
    val isClan: Boolean
) {
    fun toContentDescription(): String =
        "${if (isClan) "Clan" else "Global"} chat. $sender says: $message"
}

/**
 * Gear item in the hero's inventory
 */
data class GearItem(
    val id: String,
    val name: String,
    val slot: GearSlot,
    val rarity: MemoryType,
    val perks: List<GearPerk>,
    val infusionLevel: Int,
    val isEquipped: Boolean,
    val setName: String?
) {
    fun toContentDescription(): String = buildString {
        append("${rarity.label} $name.")
        append(" Slot: ${slot.label}.")
        if (perks.isNotEmpty()) {
            append(" Perks: ${perks.joinToString(", ") { it.description }}.")
        }
        if (infusionLevel > 0) append(" Infused $infusionLevel times.")
        if (isEquipped) append(" Currently equipped.")
        setName?.let { append(" Part of $it set.") }
    }
}

enum class GearSlot(val label: String) {
    HELMET("Helmet"), CHEST("Chest"), ARMS("Arms"),
    LEGS("Legs"), BOOTS("Boots"), WEAPON("Weapon")
}

data class GearPerk(val name: String, val value: Float) {
    val description: String get() = "$name +${value.toInt()}%"
}
