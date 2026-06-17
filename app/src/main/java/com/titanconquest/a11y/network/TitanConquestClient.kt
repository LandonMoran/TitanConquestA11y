package com.titanconquest.a11y.network

import com.titanconquest.a11y.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * All network calls to titanconquest.com.
 *
 * The game is a web app, so we use OkHttp to POST forms and parse HTML
 * responses with Jsoup — exactly what the browser does, making this a
 * fully compatible thin client.
 */
class TitanConquestClient {

    private val cookieJar = InMemoryCookieJar()

    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            // Identify ourselves and look like a real Android browser
            val req = chain.request().newBuilder()
                .header("User-Agent", "TitanConquestA11y/1.0 Android")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()
            chain.proceed(req)
        }
        .build()

    companion object {
        private const val BASE = "https://titanconquest.com"
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /**
     * Log in with username + password. Returns the session on success.
     * Throws [LoginException] on bad credentials.
     */
    suspend fun login(username: String, password: String): Session {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("submit", "Login")
            .build()

        val response = post("$BASE/login.php", body)
        val doc = Jsoup.parse(response)

        // If we're still on the login page with an error, credentials were wrong
        if (doc.select(".error, .alert-danger").isNotEmpty() ||
            doc.title().contains("Login", ignoreCase = true)) {
            throw LoginException("Invalid username or password")
        }

        return Session(
            username = username,
            cookies = cookieJar.getCookies(BASE)
        )
    }

    fun logout() {
        cookieJar.clear()
    }

    // ── Hero / Dashboard ──────────────────────────────────────────────────────

    /**
     * Fetch the hero's current stats from the main game page.
     */
    suspend fun fetchHeroStats(): HeroStats {
        val html = get("$BASE/game.php")
        val doc = Jsoup.parse(html)
        return parseHeroStats(doc)
    }

    private fun parseHeroStats(doc: Document): HeroStats {
        // Titan Conquest uses a fairly consistent layout; selectors may need
        // adjusting after live testing against the real site.
        fun text(selector: String) = doc.select(selector).first()?.text()?.trim() ?: "0"
        fun textOrEmpty(selector: String) = doc.select(selector).first()?.text()?.trim() ?: ""

        val name = textOrEmpty(".hero-name, #heroName, .player-name")
            .ifEmpty { "Hero" }
        val level = text(".hero-level, #heroLevel, .level").toIntOrNull() ?: 1
        val hp = text(".hp-current, #hpCurrent").toIntOrNull() ?: 100
        val maxHp = text(".hp-max, #hpMax").toIntOrNull() ?: 100
        val attack = text(".stat-attack, #statAttack").toIntOrNull() ?: 0
        val defense = text(".stat-defense, #statDefense").toIntOrNull() ?: 0
        val power = text(".power, #power").toIntOrNull() ?: 0
        val drachma = text(".drachma, #drachma").replace(",", "").toLongOrNull() ?: 0L
        val xp = text(".xp-current, #xpCurrent").replace(",", "").toLongOrNull() ?: 0L
        val xpNext = text(".xp-next, #xpNext").replace(",", "").toLongOrNull() ?: 1L
        val location = textOrEmpty(".location-name, #locationName").ifEmpty { "The Acropolis" }
        val lp = text(".location-points, #locationPoints").toIntOrNull() ?: 0

        return HeroStats(name, level, hp, maxHp, attack, defense, power,
                         drachma, xp, xpNext, location, lp)
    }

    // ── Patrol / Battle ───────────────────────────────────────────────────────

    /**
     * Fetch enemies available at current location.
     */
    suspend fun fetchEnemies(): List<Enemy> {
        val html = get("$BASE/patrol.php")
        val doc = Jsoup.parse(html)
        return parseEnemies(doc)
    }

    private fun parseEnemies(doc: Document): List<Enemy> {
        return doc.select(".enemy-row, .patrol-enemy, tr.enemy").mapIndexed { i, el ->
            val rawName = el.select(".enemy-name, td.name").text().trim()
            val tierMatch = Regex("(I{2,4})$").find(rawName)
            val tier = when (tierMatch?.value) {
                "II" -> 2; "III" -> 3; "IV" -> 4; "IIII" -> 4; else -> 1
            }
            val name = rawName.replace(Regex("\\s*I{2,4}$"), "").trim()
            val hp = el.select(".enemy-hp").text().replace(",", "").toIntOrNull() ?: 100
            val maxHp = el.select(".enemy-maxhp").text().replace(",", "").toIntOrNull() ?: hp
            val isRed = el.hasClass("avenging") || el.hasClass("enemy-red")

            Enemy(
                id = el.attr("data-id").ifEmpty { "enemy_$i" },
                name = name,
                tier = tier,
                hp = hp,
                maxHp = maxHp,
                isAvenging = isRed
            )
        }
    }

    /**
     * Strike an enemy. Returns the battle result.
     */
    suspend fun strike(enemyId: String): BattleResult {
        val body = FormBody.Builder()
            .add("action", "strike")
            .add("enemy_id", enemyId)
            .build()
        val html = post("$BASE/battle.php", body)
        return parseBattleResult(html, BattleAction.STRIKE)
    }

    /**
     * Run from the current battle.
     */
    suspend fun runFromBattle(enemyId: String): BattleResult {
        val body = FormBody.Builder()
            .add("action", "run")
            .add("enemy_id", enemyId)
            .build()
        val html = post("$BASE/battle.php", body)
        return parseBattleResult(html, BattleAction.RUN)
    }

    /**
     * Use Super ability in battle.
     */
    suspend fun useSuper(enemyId: String): BattleResult {
        val body = FormBody.Builder()
            .add("action", "super")
            .add("enemy_id", enemyId)
            .build()
        val html = post("$BASE/battle.php", body)
        return parseBattleResult(html, BattleAction.USE_SUPER)
    }

    private fun parseBattleResult(html: String, action: BattleAction): BattleResult {
        val doc = Jsoup.parse(html)

        fun int(sel: String) = doc.select(sel).first()?.text()
            ?.replace(",", "")?.toIntOrNull() ?: 0
        fun long(sel: String) = doc.select(sel).first()?.text()
            ?.replace(",", "")?.toLongOrNull() ?: 0L

        val enemyDefeated = doc.select(".enemy-defeated, .kill-message").isNotEmpty()
        val playerRan = action == BattleAction.RUN

        val lootEl = doc.select(".loot-drop, .memory-drop").first()
        val loot = lootEl?.let {
            val typeStr = it.attr("data-type").uppercase()
            val memType = MemoryType.values().firstOrNull { m -> m.name == typeStr }
                ?: MemoryType.WHITE
            LootDrop(memType, it.text().trim())
        }

        return BattleResult(
            action = action,
            heroHpAfter = int(".hp-current, #hpCurrent"),
            enemyHpAfter = int(".enemy-hp-after, .enemy-hp"),
            damageDealt = int(".damage-dealt"),
            damageTaken = int(".damage-taken"),
            xpGained = long(".xp-gained"),
            drachmaGained = long(".drachma-gained"),
            lootDropped = loot,
            enemyDefeated = enemyDefeated,
            playerRan = playerRan
        )
    }

    // ── Locations ─────────────────────────────────────────────────────────────

    suspend fun fetchLocations(): List<Location> {
        val html = get("$BASE/locations.php")
        val doc = Jsoup.parse(html)
        return doc.select(".location-item, tr.location").mapIndexed { i, el ->
            val name = el.select(".location-name, td.name").text().trim()
            val locked = el.hasClass("locked") || el.select(".locked").isNotEmpty()
            val lpReq = el.attr("data-lp-required").toIntOrNull() ?: 0
            val enemyCount = el.select(".enemy-count").text().toIntOrNull() ?: 0
            Location(
                id = el.attr("data-id").ifEmpty { "loc_$i" },
                name = name.ifEmpty { "Location $i" },
                isUnlocked = !locked,
                locationPointsRequired = lpReq,
                currentEnemyCount = enemyCount
            )
        }
    }

    suspend fun travelToLocation(locationId: String) {
        val body = FormBody.Builder()
            .add("location_id", locationId)
            .build()
        post("$BASE/travel.php", body)
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    suspend fun fetchGlobalChat(): List<ChatMessage> = fetchChat(isClan = false)
    suspend fun fetchClanChat(): List<ChatMessage> = fetchChat(isClan = true)

    private suspend fun fetchChat(isClan: Boolean): List<ChatMessage> {
        val url = if (isClan) "$BASE/clanchat.php" else "$BASE/chat.php"
        val html = get(url)
        val doc = Jsoup.parse(html)
        return doc.select(".chat-message, .msg-row").map { el ->
            ChatMessage(
                sender = el.select(".chat-sender, .username").text().trim(),
                message = el.select(".chat-text, .message").text().trim(),
                timestamp = el.select(".chat-time, .time").text().trim(),
                isClan = isClan
            )
        }.reversed() // newest last for natural reading order
    }

    suspend fun sendChatMessage(message: String, isClan: Boolean) {
        val url = if (isClan) "$BASE/clanchat.php" else "$BASE/chat.php"
        val body = FormBody.Builder()
            .add("message", message)
            .add("submit", "Send")
            .build()
        post(url, body)
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private suspend fun get(url: String): String = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw NetworkException("HTTP ${res.code} for $url")
            res.body?.string() ?: ""
        }
    }

    private suspend fun post(url: String, body: FormBody): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url(url).post(body).build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw NetworkException("HTTP ${res.code} for $url")
                res.body?.string() ?: ""
            }
        }
}

// ── Cookie management ─────────────────────────────────────────────────────────

class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host] ?: emptyList()

    fun getCookies(baseUrl: String): Map<String, String> {
        val host = HttpUrl.parse(baseUrl)?.host ?: return emptyMap()
        return store[host]?.associate { it.name to it.value } ?: emptyMap()
    }

    fun clear() = store.clear()
}

// ── Exceptions ────────────────────────────────────────────────────────────────

class LoginException(message: String) : IOException(message)
class NetworkException(message: String) : IOException(message)
