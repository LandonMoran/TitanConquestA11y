package com.titanconquest.a11y

import com.titanconquest.a11y.model.*
import com.titanconquest.a11y.network.TitanConquestClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for all HTML parsing in TitanConquestClient.
 *
 * Uses realistic HTML that mirrors titanconquest.com's Framework7 structure:
 *   .item-title  = label
 *   .item-after  = value
 *   .item-content / .item-inner = row container
 *
 * These run on JVM without a device or network.
 */
class NetworkParsingTest {

    private val client = TitanConquestClient()

    // ── Hero stat parsing ─────────────────────────────────────────────────────

    @Test
    fun `parseHeroStats extracts stats from Framework7 list items`() {
        val html = """
            <html><body>
              <div class="navbar"><div class="navbar-title">Achilles</div></div>
              <ul class="list">
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">Level</div>
                    <div class="item-after">42</div>
                  </div>
                </li>
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">HP</div>
                    <div class="item-after">350/500</div>
                  </div>
                </li>
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">XP</div>
                    <div class="item-after">12000/30000</div>
                  </div>
                </li>
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">Drachma</div>
                    <div class="item-after">99,500</div>
                  </div>
                </li>
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">Power</div>
                    <div class="item-after">1200</div>
                  </div>
                </li>
                <li class="item-content">
                  <div class="item-inner">
                    <div class="item-title">LP</div>
                    <div class="item-after">87</div>
                  </div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val hero = client.parseHeroStats(Jsoup.parse(html))
        assertEquals("Achilles", hero.name)
        assertEquals(42, hero.level)
        assertEquals(350, hero.hp)
        assertEquals(500, hero.maxHp)
        assertEquals(12000L, hero.xp)
        assertEquals(30000L, hero.xpToNextLevel)
        assertEquals(99500L, hero.drachma)
        assertEquals(1200, hero.power)
        assertEquals(87, hero.locationPoints)
    }

    @Test
    fun `parseHeroStats uses safe defaults when page is empty`() {
        val hero = client.parseHeroStats(Jsoup.parse("<html><body></body></html>"))
        assertEquals("Hero", hero.name)
        assertEquals(1, hero.level)
        assertTrue(hero.hp >= 1)
        assertTrue(hero.maxHp >= 1)
    }

    @Test
    fun `hpPercent calculates correctly`() {
        val hero = HeroStats("X", 1, 50, 200, 0, 0, 0, 0, 0, 1000, "Earth", 0)
        assertEquals(0.25f, hero.hpPercent, 0.001f)
    }

    @Test
    fun `xpPercent calculates correctly`() {
        val hero = HeroStats("X", 1, 100, 100, 0, 0, 0, 0, 750, 1000, "Earth", 0)
        assertEquals(0.75f, hero.xpPercent, 0.001f)
    }

    // ── Enemy parsing ─────────────────────────────────────────────────────────

    @Test
    fun `parseEnemies extracts name and HP from Framework7 list items`() {
        val html = """
            <html><body>
              <ul class="list">
                <li>
                  <div class="item-content">
                    <div class="item-inner">
                      <div class="item-title">Cyclops</div>
                      <div class="item-after"><span class="badge">80/120</span></div>
                    </div>
                  </div>
                  <a href="patrol.php?id=101&action=attack"></a>
                </li>
                <li>
                  <div class="item-content">
                    <div class="item-inner">
                      <div class="item-title">Hydra II</div>
                      <div class="item-after"><span class="badge">200/300</span></div>
                    </div>
                  </div>
                  <a href="patrol.php?id=202&action=attack"></a>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val enemies = client.parseEnemies(Jsoup.parse(html))
        assertEquals(2, enemies.size)
        assertEquals("Cyclops", enemies[0].name)
        assertEquals(1, enemies[0].tier)
        assertEquals(80, enemies[0].hp)
        assertEquals(120, enemies[0].maxHp)
        assertEquals("101", enemies[0].id)

        assertEquals("Hydra", enemies[1].name)
        assertEquals(2, enemies[1].tier)
        assertEquals("Hydra II", enemies[1].displayName)
        assertEquals(200, enemies[1].hp)
        assertEquals("202", enemies[1].id)
    }

    @Test
    fun `parseEnemies detects tier III correctly`() {
        val html = """
            <html><body><ul>
              <li><div class="item-content"><div class="item-inner">
                <div class="item-title">Serpent III</div>
                <div class="item-after">50/100</div>
              </div></div><a href="patrol.php?id=1&action=attack"></a></li>
            </ul></body></html>
        """.trimIndent()
        val enemies = client.parseEnemies(Jsoup.parse(html))
        assertEquals(1, enemies.size)
        assertEquals(3, enemies[0].tier)
        assertEquals("Serpent", enemies[0].name)
        assertEquals("Serpent III", enemies[0].displayName)
    }

    @Test
    fun `parseEnemies detects avenging via color-red class`() {
        val html = """
            <html><body><ul>
              <li class="color-red">
                <div class="item-content"><div class="item-inner">
                  <div class="item-title">Titan</div>
                  <div class="item-after">500/500</div>
                </div></div>
                <a href="patrol.php?id=9&action=attack"></a>
              </li>
            </ul></body></html>
        """.trimIndent()
        val enemies = client.parseEnemies(Jsoup.parse(html))
        assertTrue(enemies[0].isAvenging)
    }

    // ── Battle result parsing ─────────────────────────────────────────────────

    @Test
    fun `parseBattleResult detects enemy defeated`() {
        val html = """
            <html><body>
              <div class="block color-green">Enemy defeated! You killed Cyclops!</div>
              <ul class="list">
                <li class="item-content"><div class="item-inner">
                  <div class="item-title">Damage</div>
                  <div class="item-after">45</div>
                </div></li>
                <li class="item-content"><div class="item-inner">
                  <div class="item-title">XP</div>
                  <div class="item-after">120</div>
                </div></li>
                <li class="item-content"><div class="item-inner">
                  <div class="item-title">Drachma</div>
                  <div class="item-after">50</div>
                </div></li>
                <li class="item-content"><div class="item-inner">
                  <div class="item-title">HP</div>
                  <div class="item-after">180/200</div>
                </div></li>
              </ul>
            </body></html>
        """.trimIndent()

        val result = client.parseBattleResult(html, BattleAction.STRIKE)
        assertTrue(result.enemyDefeated)
        assertFalse(result.playerRan)
        assertEquals(45, result.damageDealt)
        assertEquals(120L, result.xpGained)
        assertEquals(50L, result.drachmaGained)
    }

    @Test
    fun `parseBattleResult marks run correctly and does not flag as defeated`() {
        val html = "<html><body><div class='block'>You ran away!</div></body></html>"
        val result = client.parseBattleResult(html, BattleAction.RUN)
        assertTrue(result.playerRan)
        assertFalse(result.enemyDefeated)
    }

    @Test
    fun `parseBattleResult detects legendary loot`() {
        val html = """
            <html><body>
              <div class="item-title color-purple">Legendary Memory dropped!</div>
            </body></html>
        """.trimIndent()
        val result = client.parseBattleResult(html, BattleAction.STRIKE)
        assertNotNull(result.lootDropped)
        assertEquals(MemoryType.PURPLE, result.lootDropped?.memoryType)
    }

    // ── Attack types ──────────────────────────────────────────────────────────

    @Test
    fun `AttackType labels are correct`() {
        assertEquals("Primary", AttackType.PRIMARY.label)
        assertEquals("Special", AttackType.SPECIAL.label)
        assertEquals("Heavy",   AttackType.HEAVY.label)
    }

    @Test
    fun `AttackType descriptions mention key gameplay info`() {
        assertTrue(AttackType.PRIMARY.description.contains("spam", ignoreCase = true))
        assertTrue(AttackType.SPECIAL.description.contains("shield", ignoreCase = true))
        assertTrue(AttackType.HEAVY.description.contains("Titan", ignoreCase = true))
    }

    // ── Accessibility content descriptions ────────────────────────────────────

    @Test
    fun `enemy toContentDescription includes avenging warning`() {
        val enemy = Enemy("1", "Cyclops", 2, 80, 120, isAvenging = true)
        val desc = enemy.toContentDescription()
        assertTrue("Should mention Cyclops II", desc.contains("Cyclops II"))
        assertTrue("Should mention avenging", desc.contains("Avenging") || desc.contains("double"))
    }

    @Test
    fun `enemy displayName includes tier suffix`() {
        assertEquals("Hydra",    Enemy("1", "Hydra", 1, 100, 100, false).displayName)
        assertEquals("Hydra II", Enemy("1", "Hydra", 2, 100, 100, false).displayName)
        assertEquals("Hydra III",Enemy("1", "Hydra", 3, 100, 100, false).displayName)
    }

    @Test
    fun `bounty toContentDescription mentions claim when completed`() {
        val bounty = Bounty("1", "Kill 10 Cyclops", "500 XP", "Daily",
                            isCompleted = true, isAccepted = true, progress = 10, goal = 10)
        assertTrue(bounty.toContentDescription().contains("Completed"))
    }

    @Test
    fun `location toContentDescription shows LP requirement when locked`() {
        val loc = Location("1", "Jupiter", "Jupiter", false, 500, 50, 0)
        val desc = loc.toContentDescription()
        assertTrue(desc.contains("500") && (desc.contains("LP") || desc.contains("location points") || desc.contains("Requires")))
    }

    @Test
    fun `gear toContentDescription includes rarity and equipped status`() {
        val gear = GearItem(
            "1", "Titan Helm", GearSlot.HELMET, MemoryType.PURPLE,
            listOf(GearPerk("HP", 5f), GearPerk("Defense", 3f)),
            infusionLevel = 2, isEquipped = true, setName = "Titan"
        )
        val desc = gear.toContentDescription()
        assertTrue(desc.contains("Legendary"))
        assertTrue(desc.contains("Titan Helm"))
        assertTrue(desc.contains("Equipped"))
    }

    // ── BattleResult announcements ────────────────────────────────────────────

    @Test
    fun `BattleResult kill announcement includes XP Drachma and loot`() {
        val result = BattleResult(
            action = BattleAction.STRIKE,
            heroHpAfter = 180, heroMaxHp = 200,
            enemyHpAfter = 0, damageDealt = 45, damageTaken = 10,
            xpGained = 120, drachmaGained = 50,
            lootDropped = LootDrop(MemoryType.BLUE, "Blue Memory"),
            enemyDefeated = true, playerRan = false
        )
        val a = result.toAnnouncement()
        assertTrue(a.contains("120 XP"))
        assertTrue(a.contains("50 Drachma"))
        assertTrue(a.contains("Blue Memory"))
    }

    @Test
    fun `BattleResult run announcement mentions HP restored`() {
        val result = BattleResult(
            action = BattleAction.RUN,
            heroHpAfter = 200, heroMaxHp = 200,
            enemyHpAfter = 80, damageDealt = 0, damageTaken = 0,
            xpGained = 0, drachmaGained = 0,
            lootDropped = null, enemyDefeated = false, playerRan = true
        )
        assertTrue(result.toAnnouncement().contains("HP fully restored"))
    }

    @Test
    fun `BattleResult combo is mentioned in announcement`() {
        val result = BattleResult(
            action = BattleAction.STRIKE,
            heroHpAfter = 100, heroMaxHp = 100,
            enemyHpAfter = 0, damageDealt = 200, damageTaken = 0,
            xpGained = 500, drachmaGained = 100,
            lootDropped = null, enemyDefeated = true, playerRan = false,
            comboCount = 5
        )
        assertTrue(result.toAnnouncement().contains("5"))
    }
}
