package com.titanconquest.a11y.network

import com.titanconquest.a11y.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for titanconquest.com.
 *
 * The game is a server-rendered PHP web app. Every action is a form POST
 * or GET, and the server returns HTML we parse with Jsoup. This is exactly
 * what the browser does, so we're a fully compatible thin client.
 *
 * Networking is based on careful inspection of the public site structure.
 * Selectors may need tuning once tested against a live account.
 */
class TitanConquestClient {

    private val cookieJar = InMemoryCookieJar()

    val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "TitanConquestA11y/1.0 (Android; accessible client)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Origin", BASE)
                .header("Referer", "$BASE/")
                .build()
            chain.proceed(req)
        }
        .build()

    companion object {
        const val BASE = "https://titanconquest.com"
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String): Session {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("submit", "Login")
            .build()

        val html = post("$BASE/login.php", body)
        val doc = Jsoup.parse(html)

        // Still on login page = bad credentials
        val isLoginPage = doc.select("input[name=password]").isNotEmpty()
        val hasError = doc.select(".error, .alert-danger, .login-error").isNotEmpty()
        if (isLoginPage || hasError) {
            val msg = doc.select(".error, .alert-danger, .login-error").text()
                .ifEmpty { "Invalid username or password." }
            throw LoginException(msg)
        }

        return Session(username, cookieJar.getCookies(BASE))
    }

    fun logout() = cookieJar.clear()

    fun isLoggedIn(): Boolean = cookieJar.getCookies(BASE).isNotEmpty()

    // ── Hero stats ────────────────────────────────────────────────────────────
    // The main game page shows stats in a sidebar/header area.
    // We fetch the patrol page since it shows both stats and enemies.

    suspend fun fetchHeroStats(): HeroStats {
        val html = get("$BASE/patrol.php")
        return parseHeroStats(Jsoup.parse(html))
    }

    fun parseHeroStats(doc: Document): HeroStats {
        // Helper: get text of first matching element, fallback to default
        fun t(vararg selectors: String, default: String = "0"): String {
            for (sel in selectors) {
                val el = doc.select(sel).first()
                if (el != null) return el.text().trim()
            }
            return default
        }

        // Hero name — typically shown as a header or in a stat block
        val name = t(".hero-name", "#heroName", ".player-name", "h2.name",
                      default = "Hero")
            .ifEmpty { "Hero" }

        val level = t(".hero-level", ".level", "#level", "[data-stat=level]",
                       "span:containsOwn(Level)").filter { it.isDigit() }.toIntOrNull() ?: 1

        // HP — "80 / 200" or separate elements
        val hpText = t(".hp", "#hp", "[data-stat=hp]", ".stat-hp")
        val hpParts = hpText.split("/").map { it.trim().replace(",", "") }
        val hp    = hpParts.getOrNull(0)?.toIntOrNull() ?: 100
        val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: hp

        val attack  = t(".atk", ".attack", "#attack", "[data-stat=atk]")
            .replace(",","").replace("%","").toIntOrNull() ?: 0
        val defense = t(".def", ".defense", "#defense", "[data-stat=def]")
            .replace(",","").replace("%","").toIntOrNull() ?: 0
        val power   = t(".power", "#power", "[data-stat=power]", ".decode-rank")
            .replace(",","").toIntOrNull() ?: 0
        val drachma = t(".drachma", "#drachma", "[data-stat=drachma]", ".currency-drachma")
            .replace(",","").replace("D","").trim().toLongOrNull() ?: 0L

        // XP: "1234 / 5000" or separate
        val xpText = t(".xp", "#xp", "[data-stat=xp]", ".stat-xp")
        val xpParts = xpText.split("/").map { it.trim().replace(",","") }
        val xp     = xpParts.getOrNull(0)?.toLongOrNull() ?: 0L
        val xpNext = xpParts.getOrNull(1)?.toLongOrNull() ?: 1L

        val location = t(".location", "#location", ".current-location", "[data-location]",
                          default = "The Acropolis").ifEmpty { "The Acropolis" }
        val lp = t(".lp", "#lp", "[data-stat=lp]", ".location-points")
            .replace(",","").toIntOrNull() ?: 0

        val vm = t(".vm", "#vm", "[data-stat=vm]", ".vanguard-marks")
            .replace(",","").toIntOrNull() ?: 0
        val ac = t(".ac", "#ac", "[data-stat=ac]", ".ancient-coins")
            .replace(",","").toIntOrNull() ?: 0
        val cm = t(".cm", "#cm", "[data-stat=cm]", ".clan-marks")
            .replace(",","").toIntOrNull() ?: 0

        // Class detection from page text
        val pageText = doc.body()?.text() ?: ""
        val heroClass = when {
            pageText.contains("Augur", ignoreCase = true) -> HeroClass.AUGUR
            pageText.contains("Giant", ignoreCase = true) -> HeroClass.GIANT
            else -> HeroClass.WARRIOR
        }

        return HeroStats(
            name = name, level = level, hp = hp, maxHp = maxHp,
            attack = attack, defense = defense, power = power,
            drachma = drachma, xp = xp, xpToNextLevel = xpNext,
            location = location, locationPoints = lp,
            vanguardMarks = vm, ancientCoins = ac, clanMarks = cm,
            heroClass = heroClass
        )
    }

    // ── Patrol / Enemies ──────────────────────────────────────────────────────

    suspend fun fetchPatrol(): Pair<HeroStats?, List<Enemy>> {
        val html = get("$BASE/patrol.php")
        val doc = Jsoup.parse(html)
        val hero = try { parseHeroStats(doc) } catch (e: Exception) { null }
        val enemies = parseEnemies(doc)
        return Pair(hero, enemies)
    }

    fun parseEnemies(doc: Document): List<Enemy> {
        // TC shows enemies as table rows or list items with attack links/buttons
        val rows = doc.select("tr.enemy, .enemy-row, .mob-row, li.enemy, .patrol-item")
        if (rows.isNotEmpty()) {
            return rows.mapIndexed { i, el ->
                val rawName = el.select(".enemy-name, .mob-name, td:first-child, .name").text().trim()
                val tier = parseTier(rawName)
                val name = rawName.replace(Regex("\\s+I{2,4}$"), "").trim()
                val hpRaw = el.select(".hp, .enemy-hp, td.hp").text()
                val hpParts = hpRaw.split("/").map { it.trim().replace(",","") }
                val hp    = hpParts.getOrNull(0)?.toIntOrNull() ?: 100
                val maxHp = hpParts.getOrNull(1)?.toIntOrNull() ?: hp
                val isRed = el.hasClass("red") || el.hasClass("avenging") ||
                            el.attr("style").contains("red")
                Enemy(
                    id = el.attr("data-id").ifEmpty { el.select("[href]").attr("href") }
                          .ifEmpty { "e$i" },
                    name = name.ifEmpty { "Enemy" },
                    tier = tier, hp = hp, maxHp = maxHp, isAvenging = isRed
                )
            }
        }

        // Fallback: look for any links that look like attack actions
        return doc.select("a[href*=attack], a[href*=patrol], button[data-enemy]")
            .mapIndexed { i, el ->
                val rawName = el.text().trim().ifEmpty { el.attr("data-name") }
                val tier = parseTier(rawName)
                val name = rawName.replace(Regex("\\s+I{2,4}$"), "").trim()
                Enemy(
                    id = el.attr("href").ifEmpty { el.attr("data-enemy") }.ifEmpty { "e$i" },
                    name = name.ifEmpty { "Enemy" },
                    tier = tier, hp = 100, maxHp = 100, isAvenging = false
                )
            }
    }

    private fun parseTier(name: String): Int = when {
        name.endsWith(" IV") || name.endsWith(" IIII") -> 4
        name.endsWith(" III") -> 3
        name.endsWith(" II")  -> 2
        else -> 1
    }

    // ── Battle actions ────────────────────────────────────────────────────────

    suspend fun strike(enemyId: String): BattleResult {
        val html = post("$BASE/patrol.php", FormBody.Builder()
            .add("action", "attack")
            .add("enemy", enemyId)
            .add("type", "normal")
            .build())
        return parseBattleResult(html, BattleAction.STRIKE)
    }

    suspend fun useSuper(enemyId: String): BattleResult {
        val html = post("$BASE/patrol.php", FormBody.Builder()
            .add("action", "attack")
            .add("enemy", enemyId)
            .add("type", "super")
            .build())
        return parseBattleResult(html, BattleAction.USE_SUPER)
    }

    suspend fun runFromBattle(enemyId: String): BattleResult {
        val html = post("$BASE/patrol.php", FormBody.Builder()
            .add("action", "run")
            .add("enemy", enemyId)
            .build())
        return parseBattleResult(html, BattleAction.RUN)
    }

    fun parseBattleResult(html: String, action: BattleAction): BattleResult {
        val doc = Jsoup.parse(html)

        fun int(vararg sel: String): Int {
            for (s in sel) {
                val v = doc.select(s).first()?.text()?.replace(",","")?.replace("+","")?.toIntOrNull()
                if (v != null) return v
            }
            return 0
        }
        fun long(vararg sel: String): Long {
            for (s in sel) {
                val v = doc.select(s).first()?.text()?.replace(",","")?.replace("+","")?.toLongOrNull()
                if (v != null) return v
            }
            return 0L
        }

        val defeated = doc.select(".kill, .enemy-dead, .defeated, .kill-message").isNotEmpty() ||
                       doc.text().contains("defeated", ignoreCase = true) ||
                       doc.text().contains("you killed", ignoreCase = true)
        val ran = action == BattleAction.RUN

        val lootEl = doc.select(".loot, .memory, .drop, [data-rarity]").firstOrNull()
        val loot = lootEl?.let {
            val rarityStr = (it.attr("data-rarity").ifEmpty {
                it.className().split(" ").firstOrNull { c ->
                    c in listOf("white","green","blue","purple","red") } ?: "white"
            }).uppercase()
            val memType = MemoryType.values().firstOrNull { m -> m.name == rarityStr } ?: MemoryType.WHITE
            LootDrop(memType, it.text().trim().ifEmpty { "${memType.label} Memory" })
        }

        // HP after battle — re-parse the updated stats block
        val hpText = doc.select(".hp, #hp, [data-stat=hp]").first()?.text() ?: ""
        val hpParts = hpText.split("/").map { it.trim().replace(",","") }
        val heroHpAfter = hpParts.getOrNull(0)?.toIntOrNull() ?: 0
        val heroMaxHp   = hpParts.getOrNull(1)?.toIntOrNull() ?: 100

        val combo = doc.select(".combo, [data-combo]").first()?.text()
            ?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        return BattleResult(
            action = action,
            heroHpAfter = heroHpAfter,
            heroMaxHp = heroMaxHp,
            enemyHpAfter = int(".enemy-hp", ".mob-hp"),
            damageDealt = int(".damage", ".dmg-dealt", ".hit"),
            damageTaken = int(".damage-taken", ".dmg-taken"),
            xpGained = long(".xp-gain", ".xp-gained", ".xp-reward"),
            drachmaGained = long(".drachma-gain", ".drachma-reward"),
            lootDropped = loot,
            enemyDefeated = defeated,
            playerRan = ran,
            comboCount = combo
        )
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    suspend fun fetchLocations(): List<Location> {
        val html = get("$BASE/locations.php")
        val doc = Jsoup.parse(html)
        return doc.select(".location-item, tr.location, li.location, .area-row")
            .mapIndexed { i, el ->
                val name = el.select(".location-name, .name, td:first-child").text().trim()
                val locked = el.hasClass("locked") || el.select(".lock, .locked").isNotEmpty()
                val lpReq = el.attr("data-lp").ifEmpty {
                    el.select(".lp-req").text()
                }.replace(",","").toIntOrNull() ?: 0
                val recLevel = el.attr("data-level").toIntOrNull() ?: 1
                val planet = KNOWN_LOCATIONS.firstOrNull { name.contains(it, ignoreCase = true) }
                    ?: "Unknown"
                Location(
                    id = el.attr("data-id").ifEmpty { el.select("a").attr("href") }.ifEmpty { "loc$i" },
                    name = name.ifEmpty { "Location $i" },
                    planet = planet,
                    isUnlocked = !locked,
                    locationPointsRequired = lpReq,
                    recommendedLevel = recLevel,
                    enemyCount = el.select(".enemy-count").text().toIntOrNull() ?: 0
                )
            }
    }

    suspend fun travel(locationId: String) {
        post("$BASE/locations.php", FormBody.Builder()
            .add("action", "travel")
            .add("location", locationId)
            .build())
    }

    // ── Bounties ──────────────────────────────────────────────────────────────

    suspend fun fetchBounties(): List<Bounty> {
        val html = get("$BASE/bounties.php")
        val doc = Jsoup.parse(html)
        return doc.select(".bounty, .bounty-row, tr.bounty").mapIndexed { i, el ->
            val desc    = el.select(".bounty-desc, .description, td:first-child").text().trim()
            val reward  = el.select(".bounty-reward, .reward").text().trim()
            val time    = el.select(".bounty-time, .time-limit").text().trim().ifEmpty { "Daily" }
            val done    = el.hasClass("complete") || el.hasClass("done") ||
                          el.select(".claim, .complete").isNotEmpty()
            val accepted = el.hasClass("active") || el.hasClass("accepted") || done
            val progress = el.select(".progress-val, .current").text().replace(",","").toIntOrNull() ?: 0
            val goal     = el.select(".progress-max, .goal").text().replace(",","").toIntOrNull() ?: 1
            Bounty(
                id = el.attr("data-id").ifEmpty { "b$i" },
                description = desc.ifEmpty { "Bounty ${i+1}" },
                reward = reward.ifEmpty { "Unknown" },
                timeLimit = time,
                isCompleted = done,
                isAccepted = accepted,
                progress = progress,
                goal = goal
            )
        }
    }

    suspend fun acceptBounty(bountyId: String) {
        post("$BASE/bounties.php", FormBody.Builder()
            .add("action", "accept").add("id", bountyId).build())
    }

    suspend fun claimBounty(bountyId: String) {
        post("$BASE/bounties.php", FormBody.Builder()
            .add("action", "claim").add("id", bountyId).build())
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    suspend fun fetchChat(clan: Boolean = false): List<ChatMessage> {
        val url = if (clan) "$BASE/clan.php?tab=chat" else "$BASE/chat.php"
        val doc = Jsoup.parse(get(url))
        return doc.select(".chat-msg, .message, .chat-line").map { el ->
            ChatMessage(
                sender = el.select(".sender, .username, .name").text().trim().ifEmpty { "Player" },
                message = el.select(".msg, .text, .body, p").text().trim()
                    .ifEmpty { el.ownText().trim() },
                timestamp = el.select(".time, .ts, .timestamp").text().trim(),
                isClan = clan
            )
        }
    }

    suspend fun sendChat(message: String, clan: Boolean = false) {
        val url = if (clan) "$BASE/clan.php" else "$BASE/chat.php"
        post(url, FormBody.Builder()
            .add("action", "send")
            .add("message", message)
            .add("submit", "Send")
            .build())
    }

    // ── Gear / Inventory ──────────────────────────────────────────────────────

    suspend fun fetchGear(): List<GearItem> {
        val doc = Jsoup.parse(get("$BASE/gear.php"))
        return doc.select(".gear-item, .item, tr.gear").mapIndexed { i, el ->
            val name    = el.select(".item-name, .name, td:first-child").text().trim()
            val rarityStr = (el.attr("data-rarity").ifEmpty {
                el.className().split(" ").firstOrNull { it in
                    listOf("white","green","blue","purple","red") } ?: "white"
            }).uppercase()
            val rarity = MemoryType.values().firstOrNull { it.name == rarityStr } ?: MemoryType.WHITE
            val slotStr = el.attr("data-slot").uppercase().ifEmpty { "WEAPON" }
            val slot = GearSlot.values().firstOrNull { it.name == slotStr } ?: GearSlot.WEAPON
            val inf   = el.select(".inf, .infusion").text().filter { it.isDigit() }.toIntOrNull() ?: 0
            val equipped = el.hasClass("equipped") || el.select(".equipped").isNotEmpty()
            val setName = el.select(".set-name, [data-set]").text().trim().ifEmpty { null }
            val perks = el.select(".perk").mapNotNull { p ->
                val ptext = p.text().trim()
                val match = Regex("(.+?)\\s*\\+?([\\d.]+)%?$").find(ptext)
                if (match != null) GearPerk(match.groupValues[1].trim(), match.groupValues[2].toFloat())
                else null
            }
            GearItem(
                id = el.attr("data-id").ifEmpty { "g$i" },
                name = name.ifEmpty { "Gear ${i+1}" },
                slot = slot, rarity = rarity, perks = perks,
                infusionLevel = inf, isEquipped = equipped, setName = setName
            )
        }
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
        val req = Request.Builder().url(url).post(body).build()
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
        val host = HttpUrl.parse(baseUrl)?.host ?: return emptyMap()
        return store[host]?.associate { it.name to it.value } ?: emptyMap()
    }

    fun clear() = store.clear()
}

// ── Exceptions ────────────────────────────────────────────────────────────────

class LoginException(message: String) : IOException(message)
class NetworkException(message: String) : IOException(message)
