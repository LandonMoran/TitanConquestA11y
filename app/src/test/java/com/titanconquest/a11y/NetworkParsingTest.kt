package com.titanconquest.a11y

import com.titanconquest.a11y.model.*
import com.titanconquest.a11y.network.TitanConquestClient
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for HTML parsing logic in TitanConquestClient.
 * These run on JVM without a device, using mocked HTML that mirrors
 * what titanconquest.com actually sends.
 */
class NetworkParsingTest {

    private val client = TitanConquestClient()

    // ── Hero stat parsing ─────────────────────────────────────────────────────

    @Test
    fun `parseHeroStats extracts name and level`() {
        val html = """
            <html><body>
              <span class="hero-name">Achilles</span>
              <span class="hero-level">42</span>
              <span class="hp">350 / 500</span>
              <span class="xp">12000 / 30000</span>
              <span class="drachma">99500</span>
              <span class="power">1200</span>
              <span class="location">Mars</span>
              <span class="lp">87</span>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val hero = client.parseHeroStats(doc)
        assertEquals("Achilles", hero.name)
        assertEquals(42, hero.level)
        assertEquals(350, hero.hp)
        assertEquals(500, hero.maxHp)
        assertEquals(12000L, hero.xp)
        assertEquals(30000L, hero.xpToNextLevel)
        assertEquals(99500L, hero.drachma)
        assertEquals(1200, hero.power)
        assertEquals("Mars", hero.location)
        assertEquals(87, hero.locationPoints)
    }

    @Test
    fun `parseHeroStats uses sensible defaults when elements missing`() {
        val doc = Jsoup.parse("<html><body></body></html>")
        val hero = client.parseHeroStats(doc)
        assertEquals("Hero", hero.name)
        assertEquals(1, hero.level)
        assertEquals(100, hero.hp)
    }

    @Test
    fun `hpPercent is correct`() {
        val hero = HeroStats("X", 1, 50, 200, 0, 0, 0, 0, 0, 1000, "Earth", 0)
        assertEquals(0.25f, hero.hpPercent, 0.001f)
    }

    @Test
    fun `xpPercent is correct`() {
        val hero = HeroStats("X", 1, 100, 100, 0, 0, 0, 0, 750, 1000, "Earth", 0)
        assertEquals(0.75f, hero.xpPercent, 0.001f)
    }

    // ── Enemy parsing ─────────────────────────────────────────────────────────

    @Test
    fun `parseEnemies returns list from table rows`() {
        val html = """
            <html><body>
              <table>
                <tr class="enemy">
                  <td class="name">Cyclops</td>
                  <td class="hp">80 / 120</td>
                </tr>
                <tr class="enemy">
                  <td class="name">Cyclops II</td>
                  <td class="hp">150 / 200</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()
        val enemies = client.parseEnemies(Jsoup.parse(html))
        assertEquals(2, enemies.size)
        assertEquals("Cyclops", enemies[0].name)
        assertEquals(1, enemies[0].tier)
        assertEquals(80, enemies[0].hp)
        assertEquals("Cyclops", enemies[1].name)
        assertEquals(2, enemies[1].tier)
        assertEquals("Cyclops II", enemies[1].displayName)
    }

    @Test
    fun `avenging flag detected from class`() {
        val html = """
            <html><body>
              <tr class="enemy red">
                <td class="name">Hydra</td>
                <td class="hp">200 / 200</td>
              </tr>
            </body></html>
        """.trimIndent()
        val enemies = client.parseEnemies(Jsoup.parse(html))
        assertTrue(enemies[0].isAvenging)
    }

    // ── Battle result parsing ─────────────────────────────────────────────────

    @Test
    fun `parseBattleResult detects enemy defeated`() {
        val html = """
            <html><body>
              <div class="kill-message">Enemy defeated!</div>
              <span class="damage">45</span>
              <span class="xp-gain">120</span>
              <span class="drachma-gain">50</span>
              <span class="hp">180 / 200</span>
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
    fun `parseBattleResult marks run correctly`() {
        val html = "<html><body><span class='hp'>200 / 200</span></body></html>"
        val result = client.parseBattleResult(html, BattleAction.RUN)
        assertTrue(result.playerRan)
        assertFalse(result.enemyDefeated)
    }

    // ── Model content descriptions ────────────────────────────────────────────

    @Test
    fun `enemy contentDescription includes avenging warning`() {
        val enemy = Enemy("1", "Cyclops", 2, 80, 120, isAvenging = true)
        val desc = enemy.toContentDescription()
        assertTrue(desc.contains("Cyclops II"))
        assertTrue(desc.contains("Avenging"))
    }

    @Test
    fun `bounty contentDescription mentions claim when complete`() {
        val bounty = Bounty("1", "Kill 10 enemies", "500 XP", "Daily",
                            isCompleted = true, isAccepted = true, progress = 10, goal = 10)
        assertTrue(bounty.toContentDescription().contains("Completed"))
    }

    @Test
    fun `location locked description mentions LP requirement`() {
        val loc = Location("1", "Jupiter", "Jupiter", false, 500, 50, 0)
        assertTrue(loc.toContentDescription().contains("500"))
        assertTrue(loc.toContentDescription().contains("locked").not().or(
            loc.toContentDescription().contains("Requires")))
    }

    @Test
    fun `gear contentDescription includes rarity and perks`() {
        val gear = GearItem(
            "1", "Titan Helm", GearSlot.HELMET, MemoryType.PURPLE,
            listOf(GearPerk("HP", 5f), GearPerk("Defense", 3f)),
            infusionLevel = 2, isEquipped = true, setName = "Titan"
        )
        val desc = gear.toContentDescription()
        assertTrue(desc.contains("Legendary"))
        assertTrue(desc.contains("Titan Helm"))
        assertTrue(desc.contains("HP +5%"))
        assertTrue(desc.contains("Equipped"))
    }

    // ── Battle announcement text ──────────────────────────────────────────────

    @Test
    fun `BattleResult announcement mentions xp and drachma on kill`() {
        val result = BattleResult(
            action = BattleAction.STRIKE,
            heroHpAfter = 180, heroMaxHp = 200,
            enemyHpAfter = 0, damageDealt = 45, damageTaken = 10,
            xpGained = 120, drachmaGained = 50,
            lootDropped = LootDrop(MemoryType.BLUE, "Rare Chest"),
            enemyDefeated = true, playerRan = false
        )
        val announcement = result.toAnnouncement()
        assertTrue(announcement.contains("120 XP"))
        assertTrue(announcement.contains("50 Drachma"))
        assertTrue(announcement.contains("Rare Chest"))
    }

    @Test
    fun `BattleResult run announcement says HP restored`() {
        val result = BattleResult(
            action = BattleAction.RUN,
            heroHpAfter = 200, heroMaxHp = 200,
            enemyHpAfter = 80, damageDealt = 0, damageTaken = 0,
            xpGained = 0, drachmaGained = 0,
            lootDropped = null, enemyDefeated = false, playerRan = true
        )
        assertTrue(result.toAnnouncement().contains("HP fully restored"))
    }
}
