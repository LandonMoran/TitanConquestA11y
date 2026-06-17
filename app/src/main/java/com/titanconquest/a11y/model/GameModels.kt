package com.titanconquest.a11y.model

// ── Hero ──────────────────────────────────────────────────────────────────────

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
    val locationPoints: Int,
    val vanguardMarks: Int = 0,
    val ancientCoins: Int = 0,
    val clanMarks: Int = 0,
    val heroClass: HeroClass = HeroClass.WARRIOR,
    val subclass: String = ""
) {
    val hpPercent: Float get() = if (maxHp > 0) hp.toFloat() / maxHp else 0f
    val xpPercent: Float get() = if (xpToNextLevel > 0) xp.toFloat() / xpToNextLevel else 0f

    fun toContentDescription(): String =
        "$name, Level $level, ${heroClass.label}. " +
        "HP: $hp of $maxHp. Power: $power. " +
        "Drachma: $drachma. XP: $xp of $xpToNextLevel to next level. " +
        "Location: $location. Location Points: $locationPoints."
}

enum class HeroClass(val label: String, val bonusDesc: String) {
    WARRIOR("Warrior", "5% bonus Attack and Location Points"),
    AUGUR("Augur", "5% bonus XP and Drachma"),
    GIANT("Giant", "5% bonus HP and Defense")
}

// ── Subclasses ────────────────────────────────────────────────────────────────

data class SubclassAbility(
    val name: String,
    val abilityName: String,
    val description: String,
    val maxCharges: Int,
    val currentCharges: Int,
    val heroClass: HeroClass
) {
    fun toContentDescription(): String =
        "$abilityName: $description. Charges: $currentCharges of $maxCharges available."
}

val ALL_SUBCLASSES = listOf(
    SubclassAbility("Blademaster",  "Strike Blade",     "Extra strong attack",     2,  2, HeroClass.WARRIOR),
    SubclassAbility("Bladeseeker",  "Fierce Blade",     "Restores HP",             1,  1, HeroClass.WARRIOR),
    SubclassAbility("Nightmaster",  "Shadow Blade",     "Reduces enemy attack",    10, 10, HeroClass.WARRIOR),
    SubclassAbility("Nullwalker",   "Flare Bomb",       "Extra strong attack",     2,  2, HeroClass.AUGUR),
    SubclassAbility("Lightsong",    "Illumina",         "Increased XP gains",      5,  5, HeroClass.AUGUR),
    SubclassAbility("Stormbringer", "Flashtrance",      "Reduces enemy attack",    10, 10, HeroClass.AUGUR),
    SubclassAbility("Gemini",       "Fist of Rage",     "Extra strong attack",     2,  2, HeroClass.GIANT),
    SubclassAbility("Wardbreaker",  "Hammer of Zeus",   "Increased Drachma gains", 5,  5, HeroClass.GIANT),
    SubclassAbility("Genji",        "Two Handed Block", "Reduces enemy attack",    10, 10, HeroClass.GIANT)
)

// ── Enemy ─────────────────────────────────────────────────────────────────────

data class Enemy(
    val id: String,
    val name: String,
    val tier: Int,
    val hp: Int,
    val maxHp: Int,
    val isAvenging: Boolean,
    val location: String = ""
) {
    val displayName: String get() = if (tier > 1) "$name ${"I".repeat(tier)}" else name
    val hpPercent: Float get() = if (maxHp > 0) hp.toFloat() / maxHp else 0f

    fun toContentDescription(): String {
        val base = "$displayName. HP: $hp of $maxHp."
        return if (isAvenging) "$base Avenging — double rewards!" else base
    }
}

// ── Battle ────────────────────────────────────────────────────────────────────

data class BattleResult(
    val action: BattleAction,
    val heroHpAfter: Int,
    val heroMaxHp: Int,
    val enemyHpAfter: Int,
    val damageDealt: Int,
    val damageTaken: Int,
    val xpGained: Long,
    val drachmaGained: Long,
    val lootDropped: LootDrop?,
    val enemyDefeated: Boolean,
    val playerRan: Boolean,
    val statusEffect: String? = null,
    val comboCount: Int = 0
) {
    fun toAnnouncement(): String = buildString {
        when {
            playerRan -> append("You ran. HP fully restored.")
            enemyDefeated -> {
                append("Enemy defeated! ")
                append("You dealt $damageDealt damage. ")
                if (damageTaken > 0) append("Took $damageTaken damage. ")
                append("Gained $xpGained XP and $drachmaGained Drachma. ")
                if (comboCount > 1) append("Combo x$comboCount! ")
                lootDropped?.let { append("Loot: ${it.description}. ") }
            }
            else -> {
                append("You dealt $damageDealt damage. ")
                if (damageTaken > 0) append("Took $damageTaken. ")
                append("Enemy HP: $enemyHpAfter remaining. ")
                statusEffect?.let { append("$it. ") }
            }
        }
    }
}

enum class BattleAction { STRIKE, RUN, TAKE_COVER, USE_SUPER }

data class LootDrop(val memoryType: MemoryType, val itemName: String) {
    val description: String get() = "${memoryType.label} memory — $itemName"
}

enum class MemoryType(val label: String, val color: Long) {
    WHITE("Common", 0xFFBDBDBD),
    GREEN("Uncommon", 0xFF66BB6A),
    BLUE("Rare", 0xFF42A5F5),
    PURPLE("Legendary", 0xFFAB47BC),
    RED("Triumphant", 0xFFEF5350)
}

// ── Location ──────────────────────────────────────────────────────────────────

data class Location(
    val id: String,
    val name: String,
    val planet: String,
    val isUnlocked: Boolean,
    val locationPointsRequired: Int,
    val recommendedLevel: Int,
    val enemyCount: Int
) {
    fun toContentDescription(): String = if (isUnlocked)
        "$name on $planet. $enemyCount enemies present. Recommended level $recommendedLevel."
    else
        "$name on $planet. Locked. Requires $locationPointsRequired location points."
}

// Known locations from the game (planets/areas)
val KNOWN_LOCATIONS = listOf(
    "Earth", "The Moon", "Mars", "The Asteroid Belt",
    "Jupiter", "Saturn", "Uranus", "Neptune",
    "The Acropolis", "The Olympus Gate", "The Kronos Labyrinth"
)

// ── Bounty ────────────────────────────────────────────────────────────────────

data class Bounty(
    val id: String,
    val description: String,
    val reward: String,
    val timeLimit: String,
    val isCompleted: Boolean,
    val isAccepted: Boolean,
    val progress: Int,
    val goal: Int
) {
    val progressPercent: Float get() = if (goal > 0) progress.toFloat() / goal else 0f

    fun toContentDescription(): String = buildString {
        append(description)
        append(". Progress: $progress of $goal.")
        append(" Reward: $reward.")
        append(" Time limit: $timeLimit.")
        when {
            isCompleted -> append(" Completed — ready to claim!")
            isAccepted  -> append(" In progress.")
            else        -> append(" Not yet accepted.")
        }
    }
}

// ── Chat ──────────────────────────────────────────────────────────────────────

data class ChatMessage(
    val sender: String,
    val message: String,
    val timestamp: String,
    val isClan: Boolean
) {
    fun toContentDescription(): String =
        "${if (isClan) "Clan" else "Global"} chat. $sender: $message. $timestamp."
}

// ── Gear ──────────────────────────────────────────────────────────────────────

data class GearItem(
    val id: String,
    val name: String,
    val slot: GearSlot,
    val rarity: MemoryType,
    val perks: List<GearPerk>,
    val infusionLevel: Int,
    val isEquipped: Boolean,
    val setName: String? = null
) {
    fun toContentDescription(): String = buildString {
        append("${rarity.label} $name. Slot: ${slot.label}.")
        if (perks.isNotEmpty()) append(" Perks: ${perks.joinToString(", ") { it.description }}.")
        if (infusionLevel > 0) append(" Infused $infusionLevel times.")
        if (isEquipped) append(" Equipped.")
        setName?.let { append(" $it set.") }
    }
}

enum class GearSlot(val label: String) {
    HELMET("Helmet"), CHEST("Chest"), ARMS("Arms"),
    LEGS("Legs"), BOOTS("Boots"), WEAPON("Weapon")
}

data class GearPerk(val name: String, val value: Float) {
    val description: String get() = "$name +${value.toInt()}%"
}

// ── Session ───────────────────────────────────────────────────────────────────

data class Session(val username: String, val cookies: Map<String, String>)

// ── Attack types (from wiki) ──────────────────────────────────────────────────
// Primary: fastest, spammable. Special: 2x vs shields. Heavy: 1.5x, slowest.

enum class AttackType(val label: String, val description: String) {
    PRIMARY("Primary", "Fastest attack — spammable, effective against all enemies"),
    SPECIAL("Special", "Deals double damage to shields — use when enemy has a shield"),
    HEAVY("Heavy",   "Hardest hit with 50% bonus — best for Titans and tough enemies")
}
