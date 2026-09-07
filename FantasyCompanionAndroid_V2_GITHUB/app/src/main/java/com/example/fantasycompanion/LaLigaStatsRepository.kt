package com.example.fantasycompanion

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Consulta estadísticas públicas de LALIGA. Es una integración no oficial y puede cambiar.
 * La clave pública de APIM se obtiene dinámicamente desde laliga.com y se mantiene solo en memoria.
 */
class LaLigaStatsRepository {
    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, PlayerStats>()
    @Volatile private var subscriptionKey: String? = null

    fun get(playerName: String, callback: (PlayerStats) -> Unit) {
        val key = normalize(playerName)
        cache[key]?.let { callback(it); return }
        callback(PlayerStats(playerName = playerName, loading = true))

        thread(name = "laliga-stats", isDaemon = true) {
            val result = try {
                fetchPlayer(playerName)
            } catch (e: Exception) {
                PlayerStats(playerName = playerName, error = e.message ?: "Sin datos")
            }
            if (result.goals != null || result.assists != null || result.yellowCards != null ||
                result.redCards != null || result.cleanSheets != null) {
                cache[key] = result
            }
            main.post { callback(result) }
        }
    }

    private fun fetchPlayer(playerName: String): PlayerStats {
        val apiKey = subscriptionKey ?: discoverApiKey().also { subscriptionKey = it }
        val target = normalize(playerName)

        val subscriptions = listOf("laliga-easports-2026", "laliga-hypermotion-2026")
        for (subscription in subscriptions) {
            for (offset in 0..700 step 100) {
                val url = "https://apim.laliga.com/public-service/api/v1/subscriptions/$subscription/players/stats?limit=100&offset=$offset"
                val body = request(url, apiKey)
                val root = parseAny(body)
                val objects = collectObjects(root)
                val match = objects.firstOrNull { obj ->
                    val name = findPlayerName(obj)
                    name != null && namesMatch(target, normalize(name))
                }
                if (match != null) {
                    return statsFromObject(playerName, match)
                }
                if (objects.size < 80) break
            }
        }
        return PlayerStats(playerName = playerName, error = "Jugador no encontrado")
    }

    private fun discoverApiKey(): String {
        val pages = listOf(
            "https://www.laliga.com/",
            "https://www.laliga.com/laliga-easports"
        )
        val regexes = listOf(
            Regex("backendSubscription[\\\"']?\\s*[:=]\\s*[\\\"']([a-fA-F0-9]{24,64})"),
            Regex("Ocp-Apim-Subscription-Key[\\\"']?\\s*[:=]\\s*[\\\"']([a-fA-F0-9]{24,64})")
        )
        for (page in pages) {
            val html = request(page, null)
            for (regex in regexes) {
                regex.find(html)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        // Fallback conocido; si rota, el descubrimiento dinámico de arriba será el camino normal.
        return "c13c3a8e2f6b46da9c5c425cf61fab3e"
    }

    private fun request(url: String, apiKey: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,text/html,*/*")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FantasyCompanion/0.2")
        if (apiKey != null) connection.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) error("LALIGA HTTP $code")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAny(text: String): Any = when {
        text.trimStart().startsWith("[") -> JSONArray(text)
        else -> JSONObject(text)
    }

    private fun collectObjects(value: Any?): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> {
                    out += v
                    v.keys().forEachRemaining { key -> walk(v.opt(key)) }
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
            }
        }
        walk(value)
        return out
    }

    private fun findPlayerName(obj: JSONObject): String? {
        val directKeys = listOf("name", "nickname", "full_name", "display_name", "player_name")
        for (key in directKeys) {
            val value = obj.optString(key, "").trim()
            if (value.length in 4..50 && value.any(Char::isLetter)) return value
        }
        val person = obj.optJSONObject("person")
        if (person != null) {
            for (key in directKeys) {
                val value = person.optString(key, "").trim()
                if (value.length in 4..50) return value
            }
        }
        return null
    }

    private fun statsFromObject(requestedName: String, obj: JSONObject): PlayerStats {
        val stats = mutableMapOf<String, Int>()

        fun consumeStats(container: Any?) {
            when (container) {
                is JSONArray -> for (i in 0 until container.length()) {
                    val s = container.optJSONObject(i) ?: continue
                    val key = normalizeStatKey(
                        s.optString("name", s.optString("stat", s.optString("key", "")))
                    )
                    val raw = s.opt("value") ?: s.opt("total") ?: s.opt("stat_value")
                    val n = when (raw) {
                        is Number -> raw.toInt()
                        is String -> raw.toDoubleOrNull()?.toInt()
                        else -> null
                    }
                    if (key.isNotBlank() && n != null) stats[key] = n
                }
                is JSONObject -> container.keys().forEachRemaining { k ->
                    val raw = container.opt(k)
                    val n = when (raw) {
                        is Number -> raw.toInt()
                        is String -> raw.toDoubleOrNull()?.toInt()
                        else -> null
                    }
                    if (n != null) stats[normalizeStatKey(k)] = n
                }
            }
        }

        consumeStats(obj.opt("stats"))
        consumeStats(obj.opt("statistics"))

        // También acepta campos directos si el esquema cambia.
        obj.keys().forEachRemaining { k ->
            val raw = obj.opt(k)
            if (raw is Number) stats.putIfAbsent(normalizeStatKey(k), raw.toInt())
        }

        fun get(vararg keys: String): Int? = keys.firstNotNullOfOrNull { stats[normalizeStatKey(it)] }

        return PlayerStats(
            playerName = findPlayerName(obj) ?: requestedName,
            goals = get("goals", "goal"),
            assists = get("goal_assists", "assists", "assist"),
            yellowCards = get("yellow_cards", "yellowcards"),
            redCards = get("red_cards", "redcards", "second_yellow_red_card"),
            cleanSheets = get("clean_sheets", "cleansheets"),
            source = "LALIGA"
        )
    }

    private fun namesMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 5 && b.contains(a)) return true
        if (b.length >= 5 && a.contains(b)) return true
        val aw = a.split(' ').filter { it.length > 2 }.toSet()
        val bw = b.split(' ').filter { it.length > 2 }.toSet()
        return aw.isNotEmpty() && aw.intersect(bw).size >= minOf(2, aw.size, bw.size)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeStatKey(value: String): String = normalize(value).replace(' ', '_')
}
