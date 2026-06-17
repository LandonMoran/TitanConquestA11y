package com.titanconquest.a11y.network

import com.titanconquest.a11y.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for titanconquest.com.
 *
 * The game is a server-rendered PHP app using Framework7 for its UI.
 * Framework7 uses predictable CSS classes:
 *   .item-title     — primary text of a list item
 *   .item-after     — right-aligned label (counts, values)
 *   .item-subtitle  — secondary line
 *   .item-text      — body text
 *   .list           — list container
 *   .block          — content block / card
 *   .button         — action buttons
 *   .badge          — numeric badges (HP, counts)
 *   .card           / .card-content — card wrapper
 *   .navbar-title   — page title in top bar
 *
 * Attack types (from wiki):
 *   Primary (fastest, spammable), Special (2x vs shields), Heavy (1.5x, slowest)
 *
 * Auto-Battle: recent feature (May 2026) — toggled via Auto Booster item.
 * Sessions are cookie-based (PHP session cookie).
 */
class TitanConquestClient {

    val cookieJar = InMemoryCookieJar()

    val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(req)
        }
        .build()

    companion object {
        const val BASE = "https://titanconquest.com"
        // Known PHP pages from the game's navigation
        const val PAGE_LOGIN    = "$BASE/login.php"
        const val PAGE_HOME     = "$BASE/home.php"
        const val PAGE_PATROL   = "$BASE/patrol.php"
        const val PAGE_GEAR     = "$BASE/gear.php"
        const val PAGE_LOCATIONS = "$BASE/locations.php"
        const val PAGE_BOUNTIES = "$BASE/bounties.php"
        const val PAGE_CHAT     = "$BASE/chat.php"
        const val PAGE_MISSIONS = "$BASE/missions.php"
        const val PAGE_ACROPOLIS = "$BASE/acropolis.php"
        const val PAGE_PLAYERS  = "$BASE/players.php"
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String): Session {
        // Step 1: GET login page — grab CSRF token and establish session cookie
        val loginPageHtml = get(PAGE_LOGIN)
        val loginPageDoc = Jsoup.parse(loginPageHtml)

        // Extract hidden fields (CSRF tokens, etc.) to include in POST
        val formBuilder = FormBody.Builder()
        loginPageDoc.select("form input[type=hidden]").forEach { hidden ->
            val n = hidden.attr("name")
            val v = hidden.attr("value")
            if (n.isNotEmpty()) formBuilder.add(n, v)
        }

        // Detect actual field names from the form (some games use "user", "email", etc.)
        val userFieldName = loginPageDoc
            .select("input[type=text], input[type=email], input:not([type])")
            .firstOrNull { it.attr("name").isNotEmpty() }
            ?.attr("name") ?: "username"
        val passFieldName = loginPageDoc
            .select("input[type=password]")
            .firstOrNull { it.attr("name").isNotEmpty() }
            ?.attr("name") ?: "password"

        formBuilder
            .add(userFieldName, username)
            .add(passFieldName, password)
            .add("remember", "1")

        val html = post(PAGE_LOGIN, formBuilder.build())

        // Handle JSON response (Framework7 XHR mode)
        if (html.trimStart().startsWith("{") || html.trimStart().startsWith("[")) {
            val lower = html.lowercase()
            if (lower.contains("\"success\":true") || lower.contains("\"status\":\"ok\"") ||
                lower.contains("\"logged\":true") || lower.contains("\"loggedin\":true")) {
                return Session(username, cookieJar.getCookies(BASE))
            }
            val errMsg = Regex("\"(?:error|message|msg)\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1) ?: "Invalid username or password."
            throw LoginException(errMsg)
        }

        val doc = Jsoup.parse(html)

        // Success: redirected away from login page (no password field in form context)
        val loginForm = doc.select("form").firstOrNull { form ->
            form.select("input[type=password]").isNotEmpty()
        }
        val stillOnLogin = loginForm != null

        if (!stillOnLogin) {
            // We landed on a non-login page — success
            return Session(username, cookieJar.getCookies(BASE))
        }

        // Still on login page — extract the error message
        val errorEl = doc.select(
            ".block.inset, .toast-text, p.error, .text-color-red, " +
            ".color-red, [class*=error], [class*=alert]"
        ).firstOrNull { it.text().isNotBlank() }

        throw LoginException(
            errorEl?.text()?.trim()
                ?: doc.select(".block, .card-content").text().trim()
                    .ifBlank { "Invalid username or password." }
        )
    }

    fun logout() = cookieJar.clear()
    fun isLoggedIn() = cookieJar.getCookies(BASE).isNotEmpty()

    // ── Hero stats ────────────────────────────────────────────────────────────
    // The home/patrol page has a top bar or stat block with hero info.
    // Framework7 puts page content in .page-content > .block or .list

    suspend fun fetchHeroStats(): HeroStats {
        val html = get(PAGE_HOME)
        return parseHeroStats(Jsoup.parse(html))
    }

    fun parseHeroStats(doc: Document): HeroStats {
        // Framework7 stat items are typically:
        // <li class="item-content">
        //   <div class="item-inner">
        //     <div class="item-title">HP</div>
        //     <div class="item-after">350/500</div>
        //   </div>
        // </li>

        fun findStat(vararg labels: String): String {
            for (label in labels) {
                // Search item-title for the label, grab sibling item-after
                val el = doc.select(".item-title, .item-subtitle, b, strong, label")
                    .firstOrNull { it.text().trim().equals(label, ignoreCase = true)
                               || it.text().trim().startsWith(label, ignoreCase = true) }
                if (el != null) {
                    val after = el.parent()?.select(".item-after")?.first()?.text()?.trim()
                    if (!after.isNullOrEmpty()) return after
                    val next = el.nextElementSibling()?.text()?.trim()
                    if (!next.isNullOrEmpty()) return next
                }
                // Also try data attributes
                val dataEl = doc.select("[data-${label.lowercase().replace(" ","-")}]").first()
                if (dataEl != null) return dataEl.attr("data-${label.lowercase().replace(" ","-")}")
            }
            return ""
        }

        fun splitSlash(raw: String): Pair<Int, Int> {
            val parts = raw.replace(",","").split("/").map { it.trim().filter { c -> c.isDigit() } }
            return Pair(parts.getOrNull(0)?.toIntOrNull() ?: 0,
                        parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }

        // Name: often in .navbar-title or the first h2/h3 on the page
        val name = doc.select(".navbar-title, .page-title, h1, h2")
            .firstOrNull { it.text().length in 2..30 }?.text()?.trim()
            ?: doc.select(".item-title").firstOrNull()?.text()?.trim()
            ?: "Hero"

        // Level
        val levelRaw = findStat("Level", "Lvl", "LVL")
        val level = levelRaw.filter { it.isDigit() }.toIntOrNull()
            ?: doc.select(".badge, .item-after").map { it.text() }
                .firstOrNull { it.matches(Regex("\\d+")) && it.toInt() < 200 }
                ?.toIntOrNull() ?: 1

        // HP
        val hpRaw = findStat("HP", "Health", "Hit Points")
        val (hp, maxHp) = if (hpRaw.contains("/")) splitSlash(hpRaw)
                          else Pair(hpRaw.filter { it.isDigit() }.toIntOrNull() ?: 100, 100)

        // XP
        val xpRaw = findStat("XP", "Experience", "Exp")
        val (xp, xpNext) = if (xpRaw.contains("/")) splitSlash(xpRaw)
                            else Pair(0, 1)

        // Stats
        val atk  = findStat("ATK", "Attack", "ATK%").replace("%","").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0
        val def  = findStat("DEF", "Defense", "DEF%").replace("%","").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0
        val pwr  = findStat("Power", "PWR", "Decode Rank").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0
        val drach = findStat("Drachma", "D", "Gold").replace(",","")
                     .filter { it.isDigit() }.toLongOrNull() ?: 0L
        val lp   = findStat("LP", "Location Points", "Loc Points")
                     .replace(",","").filter { it.isDigit() }.toIntOrNull() ?: 0
        val vm   = findStat("VM", "Vanguard Marks").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0
        val ac   = findStat("AC", "Ancient Coins").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0
        val cm   = findStat("CM", "Clan Marks").replace(",","")
                     .filter { it.isDigit() }.toIntOrNull() ?: 0

        // Location: look for the current location name in nav or a heading
        val location = findStat("Location", "Area", "Zone")
            .ifEmpty { doc.select(".subtitle, .item-subtitle").firstOrNull()?.text()?.trim() ?: "The Acropolis" }

        // Class detection from page body
        val bodyText = doc.body()?.text() ?: ""
        val heroClass = when {
            bodyText.contains("Augur", ignoreCase = true) -> HeroClass.AUGUR
            bodyText.contains("Giant", ignoreCase = true) -> HeroClass.GIANT
            else -> HeroClass.WARRIOR
        }

        return HeroStats(
            name = name.ifEmpty { "Hero" }, level = level,
            hp = hp.coerceAtLeast(1), maxHp = maxHp.coerceAtLeast(1),
            attack = atk, defense = def, power = pwr,
            drachma = drach, xp = xp.toLong(), xpToNextLevel = xpNext.toLong().coerceAtLeast(1),
            location = location, locationPoints = lp,
            vanguardMarks = vm, ancientCoins = ac, clanMarks = cm,
            heroClass = heroClass
        )
    }

    // ── Patrol ────────────────────────────────────────────────────────────────
    // Framework7 list items for enemies look like:
    // <li>
    //   <div class="item-content">
    //     <div class="item-inner">
    //       <div class="item-title">Cyclops</div>
    //       <div class="item-after"><span class="badge">80/120</span></div>
    //     </div>
    //   </div>
    // </li>

    suspend fun fetchPatrol(): Pair<HeroStats?, List<Enemy>> {
        val html = get(PAGE_PATROL)
        val doc = Jsoup.parse(html)
        val hero = runCatching { parseHeroStats(doc) }.getOrNull()
        val enemies = parseEnemies(doc)
        return Pair(hero, enemies)
    }

    fun parseEnemies(doc: Document): List<Enemy> {
        // Enemies are clickable list items linking to the attack action
        // The attack link typically contains the enemy ID as a query param: ?id=123&action=attack
        val candidates = doc.select("li").filter { li ->
            val hasAttackLink = li.select("a[href*=attack], a[href*=patrol], button[onclick*=attack]").isNotEmpty()
            val hasEnemyClass = li.hasClass("enemy") || li.hasClass("mob") || li.hasClass("patrol-item")
            val hasItemContent = li.select(".item-content").isNotEmpty()
            hasAttackLink || hasEnemyClass || (hasItemContent && li.text().length > 3)
        }

        if (candidates.isEmpty()) {
            // Fallback: any clickable list items on the patrol page that look like enemies
            return doc.select(".item-content").filter { el ->
                el.select("a[href], button").isNotEmpty() &&
                el.select(".item-title").text().length > 2
            }.mapIndexed { i, el -> elementToEnemy(el, i) }
        }

        return candidates.mapIndexed { i, li -> elementToEnemy(li, i) }
    }

    private fun elementToEnemy(el: Element, index: Int): Enemy {
        // Extract name from .item-title, or the element's own text
        val rawName = el.select(".item-title").first()?.text()?.trim()
            ?: el.select("a, button").first()?.text()?.trim()
            ?: el.ownText().trim()
            ?: "Enemy $index"

        val tier = when {
            rawName.trimEnd().endsWith(" IV") || rawName.trimEnd().endsWith(" IIII") -> 4
            rawName.trimEnd().endsWith(" III") -> 3
            rawName.trimEnd().endsWith(" II")  -> 2
            else -> 1
        }
        val cleanName = rawName.replace(Regex("\\s+I{2,4}$"), "").trim()

        // HP from .item-after, .badge, or data attribute — take only the first match
        // to avoid double-counting when .badge is nested inside .item-after
        val hpRaw = (el.select(".item-after").first()
            ?: el.select(".badge, [data-hp]").first())?.text()?.trim() ?: ""
        val hpParts = hpRaw.replace(",","").split("/").map { it.trim().filter { c -> c.isDigit() } }
        val hp    = hpParts.getOrNull(0)?.toIntOrNull() ?: 100
        val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: hp.coerceAtLeast(1)

        // ID from the attack link href
        val link = el.select("a[href*=attack], a[href*=id=], a[href*=patrol]").first()
        val href = link?.attr("href") ?: ""
        val id = Regex("[?&]id=(\\d+)").find(href)?.groupValues?.get(1)
            ?: el.attr("data-id").ifEmpty { "e$index" }

        // Red/avenging: class or colour style
        val isAvenging = el.hasClass("color-red") || el.hasClass("avenging") ||
                         el.attr("style").contains("color:red", ignoreCase = true) ||
                         el.select(".badge.color-red, .text-color-red").isNotEmpty()

        return Enemy(id = id, name = cleanName.ifEmpty { "Enemy" }, tier = tier,
                     hp = hp, maxHp = maxHp, isAvenging = isAvenging)
    }

    // ── Attack actions ────────────────────────────────────────────────────────
    // The game has Primary / Special / Heavy attack types.
    // Action is POSTed to patrol.php with: id=<enemyId>&action=attack&type=primary|special|heavy

    suspend fun primaryAttack(enemyId: String) = doAttack(enemyId, "primary")
    suspend fun specialAttack(enemyId: String) = doAttack(enemyId, "special")
    suspend fun heavyAttack(enemyId: String)   = doAttack(enemyId, "heavy")

    private suspend fun doAttack(enemyId: String, type: String): BattleResult {
        val html = post(PAGE_PATROL, FormBody.Builder()
            .add("id", enemyId)
            .add("action", "attack")
            .add("type", type)
            .build())
        return parseBattleResult(html, BattleAction.STRIKE)
    }

    suspend fun useSuper(enemyId: String): BattleResult {
        val html = post(PAGE_PATROL, FormBody.Builder()
            .add("id", enemyId)
            .add("action", "super")
            .build())
        return parseBattleResult(html, BattleAction.USE_SUPER)
    }

    suspend fun runFromBattle(enemyId: String): BattleResult {
        val html = post(PAGE_PATROL, FormBody.Builder()
            .add("id", enemyId)
            .add("action", "run")
            .build())
        return parseBattleResult(html, BattleAction.RUN)
    }

    fun parseBattleResult(html: String, action: BattleAction): BattleResult {
        val doc = Jsoup.parse(html)
        val bodyText = doc.body()?.text()?.lowercase() ?: ""

        // Victory detection: "defeated", "killed", "dead", kill count incremented
        val defeated = doc.select(".color-green, .text-color-green").text()
                          .contains("kill", ignoreCase = true) ||
                       bodyText.contains("defeated") || bodyText.contains("you killed") ||
                       bodyText.contains("enemy dead") || bodyText.contains("kill!")

        val ran = action == BattleAction.RUN

        // Damage dealt — shown in a coloured block after attack
        fun extractNum(vararg keywords: String): Int {
            for (kw in keywords) {
                val el = doc.select(".item-title, .item-after, .badge, b, strong")
                    .firstOrNull { it.text().contains(kw, ignoreCase = true) }
                val numStr = el?.nextElementSibling()?.text()
                    ?: el?.parent()?.select(".item-after")?.first()?.text()
                val num = numStr?.replace(",","")?.filter { it.isDigit() }?.toIntOrNull()
                if (num != null) return num
            }
            return 0
        }

        fun extractLong(vararg keywords: String): Long {
            for (kw in keywords) {
                val el = doc.select(".item-title, .item-after, .badge, b, strong")
                    .firstOrNull { it.text().contains(kw, ignoreCase = true) }
                val numStr = el?.nextElementSibling()?.text()
                    ?: el?.parent()?.select(".item-after")?.first()?.text()
                val num = numStr?.replace(",","")?.filter { it.isDigit() }?.toLongOrNull()
                if (num != null) return num
            }
            return 0L
        }

        // HP after attack
        val hpRaw = doc.select(".item-title, b")
            .firstOrNull { it.text().contains("hp", ignoreCase = true) }
            ?.parent()?.select(".item-after")?.text() ?: ""
        val hpParts = hpRaw.replace(",","").split("/")
        val heroHpAfter = hpParts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val heroMaxHp   = hpParts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 100

        // Loot — look for memory/rarity keywords in the result block
        val lootEl = doc.select(".badge, .item-title, .item-after").firstOrNull { el ->
            RARITY_KEYWORDS.any { el.text().contains(it, ignoreCase = true) }
        }
        val loot = lootEl?.let {
            val rarityKey = RARITY_KEYWORDS.firstOrNull { k -> it.text().contains(k, ignoreCase = true) }
            val memType = when (rarityKey?.lowercase()) {
                "triumphant", "red"   -> MemoryType.RED
                "legendary", "purple" -> MemoryType.PURPLE
                "rare", "blue"        -> MemoryType.BLUE
                "uncommon", "green"   -> MemoryType.GREEN
                else                  -> MemoryType.WHITE
            }
            LootDrop(memType, it.text().trim())
        }

        val combo = doc.select(".badge, .item-after")
            .firstOrNull { it.text().contains("combo", ignoreCase = true) }
            ?.text()?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        return BattleResult(
            action = action,
            heroHpAfter = heroHpAfter, heroMaxHp = heroMaxHp,
            enemyHpAfter = extractNum("enemy hp", "mob hp", "remaining"),
            damageDealt = extractNum("damage", "dmg", "hit", "dealt"),
            damageTaken = extractNum("took", "received", "damage taken"),
            xpGained = extractLong("xp", "experience"),
            drachmaGained = extractLong("drachma", "gold", "d earned"),
            lootDropped = loot,
            enemyDefeated = defeated,
            playerRan = ran,
            comboCount = combo
        )
    }

    private val RARITY_KEYWORDS = listOf(
        "triumphant", "legendary", "rare", "uncommon", "common",
        "red memory", "purple memory", "blue memory", "green memory", "white memory"
    )

    // ── Locations ─────────────────────────────────────────────────────────────
    // Locations page lists planets/areas as Framework7 list items with
    // travel links: <a href="locations.php?id=X&action=travel">

    suspend fun fetchLocations(): List<Location> {
        val html = get(PAGE_LOCATIONS)
        val doc = Jsoup.parse(html)

        return doc.select("li").filter { li ->
            li.select(".item-content").isNotEmpty() || li.hasClass("location")
        }.mapIndexed { i, li ->
            val name = li.select(".item-title").text().trim()
                .ifEmpty { li.select("a").first()?.text()?.trim() ?: "Location $i" }
            val sub = li.select(".item-subtitle, .item-text, .item-after").text().trim()
            val locked = li.hasClass("disabled") || li.select(".icon-lock, .locked").isNotEmpty() ||
                         li.attr("style").contains("opacity", ignoreCase = true)
            val lpReq = Regex("(\\d+)\\s*LP").find(sub)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val link = li.select("a[href*=travel], a[href*=location]").first()
            val href = link?.attr("href") ?: ""
            val id = Regex("[?&]id=(\\d+)").find(href)?.groupValues?.get(1)
                ?: li.attr("data-id").ifEmpty { "loc$i" }

            Location(
                id = id, name = name, planet = name,
                isUnlocked = !locked,
                locationPointsRequired = lpReq,
                recommendedLevel = Regex("level\\s*(\\d+)", RegexOption.IGNORE_CASE).find(sub)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                enemyCount = Regex("(\\d+)\\s*enem", RegexOption.IGNORE_CASE).find(sub)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            )
        }.filter { it.name.isNotEmpty() }
    }

    suspend fun travel(locationId: String) {
        post(PAGE_LOCATIONS, FormBody.Builder()
            .add("id", locationId)
            .add("action", "travel")
            .build())
    }

    // ── Bounties ──────────────────────────────────────────────────────────────

    suspend fun fetchBounties(): List<Bounty> {
        val html = get(PAGE_BOUNTIES)
        val doc = Jsoup.parse(html)

        return doc.select("li, .card").filter { el ->
            el.select(".item-content, .card-content").isNotEmpty()
        }.mapIndexed { i, el ->
            val title = el.select(".item-title, .card-header, h3").text().trim()
            val sub   = el.select(".item-subtitle, .item-text").text().trim()
            val after = el.select(".item-after").text().trim()
            val reward = el.select("[class*=reward]").text().trim()
                .ifEmpty { Regex("Reward:?\\s*(.+?)(?:\\.|$)", RegexOption.IGNORE_CASE).find(sub)?.groupValues?.get(1)?.trim() ?: after }
            val time  = el.select("[class*=time], [class*=limit]").text().trim().ifEmpty { "Daily" }
            val done  = el.hasClass("color-green") || el.select(".icon-check, [class*=complete]").isNotEmpty()
            val accepted = done || el.select("[href*=claim], [href*=abandon], .button-red").isNotEmpty()

            val progText = el.select(".item-after, .badge").text()
            val progMatch = Regex("(\\d+)\\s*/\\s*(\\d+)").find(progText)
            val progress = progMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val goal     = progMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

            val link = el.select("a[href]").first()
            val href = link?.attr("href") ?: ""
            val id   = Regex("[?&]id=(\\d+)").find(href)?.groupValues?.get(1)
                ?: el.attr("data-id").ifEmpty { "b$i" }

            Bounty(
                id = id, description = title.ifEmpty { "Bounty ${i+1}" },
                reward = reward.ifEmpty { "Unknown" }, timeLimit = time,
                isCompleted = done, isAccepted = accepted,
                progress = progress, goal = goal
            )
        }.filter { it.description.isNotEmpty() }
    }

    suspend fun acceptBounty(bountyId: String) {
        post(PAGE_BOUNTIES, FormBody.Builder().add("id", bountyId).add("action", "accept").build())
    }

    suspend fun claimBounty(bountyId: String) {
        post(PAGE_BOUNTIES, FormBody.Builder().add("id", bountyId).add("action", "claim").build())
    }

    // ── Chat ──────────────────────────────────────────────────────────────────
    // Chat uses Framework7 messages layout:
    // <div class="messages">
    //   <div class="message message-received">
    //     <div class="message-name">Username</div>
    //     <div class="message-text">Hello!</div>
    //   </div>

    suspend fun fetchChat(clan: Boolean = false): List<ChatMessage> {
        val url = if (clan) "$BASE/clan.php?tab=chat" else PAGE_CHAT
        val doc = Jsoup.parse(get(url))

        // Try Framework7 messages structure first
        val f7Messages = doc.select(".message, .messages-content .message")
        if (f7Messages.isNotEmpty()) {
            return f7Messages.map { el ->
                ChatMessage(
                    sender = el.select(".message-name, .message-header").text().trim().ifEmpty { "Player" },
                    message = el.select(".message-text, .message-bubble").text().trim()
                                .ifEmpty { el.ownText().trim() },
                    timestamp = el.select(".message-footer, .message-date").text().trim(),
                    isClan = clan
                )
            }
        }

        // Fallback: Framework7 list items
        return doc.select("li.item-content").map { li ->
            ChatMessage(
                sender = li.select(".item-title").text().trim().ifEmpty { "Player" },
                message = li.select(".item-text, .item-subtitle").text().trim(),
                timestamp = li.select(".item-after").text().trim(),
                isClan = clan
            )
        }
    }

    suspend fun sendChat(message: String, clan: Boolean = false) {
        val url = if (clan) "$BASE/clan.php" else PAGE_CHAT
        post(url, FormBody.Builder()
            .add("message", message)
            .add("action", "send")
            .build())
    }

    // ── Gear ──────────────────────────────────────────────────────────────────

    suspend fun fetchGear(): List<GearItem> {
        val doc = Jsoup.parse(get(PAGE_GEAR))
        return doc.select("li, .card").filter { el ->
            el.select(".item-content, .card-content").isNotEmpty()
        }.mapIndexed { i, el ->
            val name    = el.select(".item-title, .card-header, h3").text().trim()
            val sub     = el.select(".item-subtitle, .item-text").text().trim()
            val after   = el.select(".item-after").text().trim()

            val rarityKey = RARITY_KEYWORDS.firstOrNull { k ->
                el.text().contains(k, ignoreCase = true) ||
                el.attr("class").contains(k, ignoreCase = true)
            }
            val rarity = when (rarityKey?.lowercase()) {
                "triumphant", "red"   -> MemoryType.RED
                "legendary", "purple" -> MemoryType.PURPLE
                "rare", "blue"        -> MemoryType.BLUE
                "uncommon", "green"   -> MemoryType.GREEN
                else                  -> MemoryType.WHITE
            }

            val slotStr = GearSlot.values()
                .firstOrNull { el.text().contains(it.label, ignoreCase = true) }
                ?: GearSlot.WEAPON

            val inf = Regex("INF\\s*(\\d+)", RegexOption.IGNORE_CASE).find(sub + after)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val equipped = el.hasClass("color-green") || sub.contains("equipped", ignoreCase = true)
            val setName  = Regex("(\\w+)\\s+[Ss]et").find(sub)?.groupValues?.get(1)

            // Perks: "+5% HP" pattern
            val perks = Regex("([A-Za-z%/ ]+?)\\s*\\+\\s*(\\d+(?:\\.\\d+)?)%").findAll(sub + " " + after)
                .map { m -> GearPerk(m.groupValues[1].trim(), m.groupValues[2].toFloat()) }
                .take(8).toList()

            val id = el.attr("data-id").ifEmpty { "g$i" }

            GearItem(id = id, name = name.ifEmpty { "Gear ${i+1}" }, slot = slotStr,
                     rarity = rarity, perks = perks, infusionLevel = inf,
                     isEquipped = equipped, setName = setName)
        }.filter { it.name.isNotEmpty() }
    }

    // ── Missions ──────────────────────────────────────────────────────────────

    suspend fun fetchMissions(): List<Bounty> {
        val doc = Jsoup.parse(get(PAGE_MISSIONS))
        return doc.select("li, .card").mapIndexed { i, el ->
            val title  = el.select(".item-title, .card-header").text().trim()
            val reward = el.select(".item-after, [class*=reward]").text().trim()
            val done   = el.hasClass("color-green") || el.select("[class*=complete]").isNotEmpty()
            Bounty(
                id = el.attr("data-id").ifEmpty { "m$i" },
                description = title.ifEmpty { return@mapIndexed null },
                reward = reward.ifEmpty { "VM reward" },
                timeLimit = "Ongoing",
                isCompleted = done, isAccepted = true, progress = 0, goal = 1
            )
        }.filterNotNull()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw NetworkException("HTTP ${res.code} for $url")
            res.body?.string() ?: throw NetworkException("Empty response from $url")
        }
    }

    suspend fun post(url: String, body: FormBody): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Referer", url)
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw NetworkException("HTTP ${res.code} for $url")
            res.body?.string() ?: throw NetworkException("Empty response from $url")
        }
    }
}

// ── Cookie jar ────────────────────────────────────────────────────────────────

class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.apply {
            removeAll { c -> cookies.any { it.name == c.name } }
            addAll(cookies)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()

    fun getCookies(baseUrl: String): Map<String, String> {
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return emptyMap()
        return store[host]?.associate { it.name to it.value } ?: emptyMap()
    }

    fun clear() = store.clear()
}

class LoginException(message: String) : IOException(message)
class NetworkException(message: String) : IOException(message)
