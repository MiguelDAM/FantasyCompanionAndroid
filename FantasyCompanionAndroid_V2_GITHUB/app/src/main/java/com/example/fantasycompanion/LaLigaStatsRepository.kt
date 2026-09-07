package com.example.fantasycompanion

import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * Consulta estadísticas públicas de LALIGA.
 *
 * Integración no oficial: la estructura de la API puede cambiar.
 */
class LaLigaStatsRepository {

    companion object {
        private const val TAG = "FantasyAPI"
    }

    private val main = Handler(Looper.getMainLooper())
    private val cache = ConcurrentHashMap<String, PlayerStats>()

    @Volatile
    private var subscriptionKey: String? = null

    fun get(playerName: String, callback: (PlayerStats) -> Unit) {
        val key = normalize(playerName)

        cache[key]?.let {
            callback(it)
            return
        }

        callback(
            PlayerStats(
                playerName = playerName,
                loading = true
            )
        )

        thread(
            name = "laliga-stats",
            isDaemon = true
        ) {
            val result = try {
                fetchPlayer(playerName)
            } catch (e: Exception) {
                Log.e(TAG, "Error buscando a $playerName", e)

                PlayerStats(
                    playerName = playerName,
                    error = e.message ?: "Sin datos"
                )
            }

            if (
                result.goals != null ||
                result.assists != null ||
                result.yellowCards != null ||
                result.redCards != null ||
                result.cleanSheets != null
            ) {
                cache[key] = result
            }

            main.post {
                callback(result)
            }
        }
    }

    // ============================================================
    // BÚSQUEDA DEL JUGADOR
    // ============================================================

    private fun fetchPlayer(playerName: String): PlayerStats {
        val apiKey = subscriptionKey
            ?: discoverApiKey().also { subscriptionKey = it }

        val target = normalize(playerName)

        Log.d(TAG, "============================================")
        Log.d(TAG, "BUSCANDO JUGADOR: $playerName")
        Log.d(TAG, "NORMALIZADO: $target")

        val subscriptions = listOf(
            "laliga-easports-2026",
            "laliga-hypermotion-2026"
        )

        for (subscription in subscriptions) {

            Log.d(TAG, "COMPETICIÓN: $subscription")

            for (offset in 0..700 step 100) {

                val url =
                    "https://apim.laliga.com/public-service/api/v1/" +
                    "subscriptions/$subscription/players/stats" +
                    "?limit=100&offset=$offset"

                val body = request(url, apiKey)
                val root = parseAny(body)

                /*
                 * La respuesta contiene muchos objetos anidados:
                 * jugadores, clubes, stats individuales, metadata...
                 *
                 * NO podemos coger simplemente el primer objeto cuyo
                 * "name" coincida.
                 */
                val objects = collectObjects(root)

                /*
                 * Solo consideramos objetos que realmente parezcan
                 * registros completos con estadísticas.
                 */
                val candidates = objects
                    .filter { hasStatsContainer(it) }
                    .mapNotNull { obj ->
                        val candidateName = findPlayerName(obj)
                            ?: return@mapNotNull null

                        val score = namesMatchScore(
                            target,
                            normalize(candidateName)
                        )

                        if (score > 0) {
                            MatchCandidate(
                                obj = obj,
                                playerName = candidateName,
                                score = score
                            )
                        } else {
                            null
                        }
                    }
                    .sortedByDescending { it.score }

                /*
                 * IMPORTANTE:
                 * ya no usamos firstOrNull().
                 *
                 * Elegimos la coincidencia con mayor puntuación.
                 */
                val match = candidates.firstOrNull()

                if (match != null && match.score >= 70) {

                    Log.d(
                        TAG,
                        "MATCH: solicitado='$playerName' " +
                            "API='${match.playerName}' " +
                            "score=${match.score}"
                    )

                    /*
                     * Log temporal para saber EXACTAMENTE qué objeto
                     * devuelve LALIGA.
                     *
                     * Lo truncamos para evitar logs gigantes.
                     */
                    val jsonDebug = match.obj.toString()

                    Log.d(
                        TAG,
                        "JSON MATCH: ${
                            jsonDebug.take(3500)
                        }"
                    )

                    return statsFromObject(
                        requestedName = playerName,
                        obj = match.obj
                    )
                }

                if (candidates.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Mejor candidato en offset $offset: " +
                            "${candidates.first().playerName} " +
                            "(${candidates.first().score})"
                    )
                }
            }
        }

        Log.w(
            TAG,
            "Jugador no encontrado: $playerName"
        )

        return PlayerStats(
            playerName = playerName,
            error = "Jugador no encontrado"
        )
    }

    private data class MatchCandidate(
        val obj: JSONObject,
        val playerName: String,
        val score: Int
    )

    // ============================================================
    // DETECCIÓN DE OBJETOS DE JUGADOR
    // ============================================================

    private fun hasStatsContainer(obj: JSONObject): Boolean {
        val stats = realValue(obj, "stats")
        val statistics = realValue(obj, "statistics")

        return stats is JSONArray ||
            stats is JSONObject ||
            statistics is JSONArray ||
            statistics is JSONObject
    }

    /**
     * Primero buscamos el nombre dentro de person/player.
     *
     * Esto es importante porque un objeto puede contener:
     *
     * name = nombre del club
     * person.name = nombre del jugador
     *
     * y antes estábamos dando prioridad al name genérico.
     */
    private fun findPlayerName(obj: JSONObject): String? {
        val keys = listOf(
            "nickname",
            "display_name",
            "player_name",
            "full_name",
            "name"
        )

        val person = obj.optJSONObject("person")

        if (person != null) {
            findNameInside(person, keys)?.let {
                return it
            }
        }

        val player = obj.optJSONObject("player")

        if (player != null) {
            findNameInside(player, keys)?.let {
                return it
            }
        }

        return findNameInside(obj, keys)
    }

    private fun findNameInside(
        obj: JSONObject,
        keys: List<String>
    ): String? {

        for (key in keys) {

            val value = obj.optString(key, "")
                .trim()

            if (
                value.length in 3..60 &&
                value.any(Char::isLetter)
            ) {
                return value
            }
        }

        return null
    }

    // ============================================================
    // COMPARACIÓN DE NOMBRES
    // ============================================================

    /**
     * Devuelve una puntuación.
     *
     * 100 = nombre exacto
     * 90  = uno contiene completamente al otro
     * 70+ = coincidencia fuerte de palabras
     *
     * Una coincidencia débil ya NO basta para escoger jugador.
     */
    private fun namesMatchScore(
        requested: String,
        candidate: String
    ): Int {

        if (
            requested.isBlank() ||
            candidate.isBlank()
        ) {
            return 0
        }

        if (requested == candidate) {
            return 100
        }

        /*
         * Ejemplo:
         *
         * "robert lewandowski"
         * "lewandowski"
         */
        if (
            requested.length >= 5 &&
            candidate.length >= 5
        ) {

            if (
                requested.contains(candidate) ||
                candidate.contains(requested)
            ) {
                return 90
            }
        }

        val requestedWords = requested
            .split(' ')
            .filter { it.length > 2 }
            .toSet()

        val candidateWords = candidate
            .split(' ')
            .filter { it.length > 2 }
            .toSet()

        if (
            requestedWords.isEmpty() ||
            candidateWords.isEmpty()
        ) {
            return 0
        }

        val common = requestedWords
            .intersect(candidateWords)

        /*
         * Para nombres de varias palabras exigimos
         * al menos dos coincidencias cuando sea posible.
         */
        if (
            requestedWords.size >= 2 &&
            candidateWords.size >= 2
        ) {

            if (common.size >= 2) {
                return 80 + minOf(common.size, 3)
            }

            return 0
        }

        /*
         * Jugadores conocidos por un único nombre:
         * Pedri, Gavi, Raphinha...
         */
        if (
            common.isNotEmpty() &&
            (
                requestedWords.size == 1 ||
                candidateWords.size == 1
            )
        ) {
            return 75
        }

        return 0
    }

    // ============================================================
    // CONVERSIÓN DEL JSON A PlayerStats
    // ============================================================

    private fun statsFromObject(
        requestedName: String,
        obj: JSONObject
    ): PlayerStats {

        val stats = mutableMapOf<String, Int>()

        fun consumeStats(container: Any?) {

            when (container) {

                is JSONArray -> {

                    for (i in 0 until container.length()) {

                        val statObject =
                            container.optJSONObject(i)
                                ?: continue

                        val key = extractStatKey(statObject)
                            ?: continue

                        val raw = firstRealValue(
                            statObject,
                            "value",
                            "total",
                            "stat_value",
                            "stat",
                            "amount",
                            "count"
                        )

                        val number = toIntOrNull(raw)

                        Log.d(
                            TAG,
                            "STAT JSON: key='$key' raw='$raw' number=$number"
                        )

                        if (number != null) {
                            stats[normalizeStatKey(key)] = number
                        }
                    }
                }

                is JSONObject -> {

                    container.keys().forEachRemaining { key ->

                        val raw = container.opt(key)

                        if (
                            raw == null ||
                            raw === JSONObject.NULL
                        ) {
                            return@forEachRemaining
                        }

                        val number = toIntOrNull(raw)

                        if (number != null) {
                            stats[
                                normalizeStatKey(key)
                            ] = number
                        }
                    }
                }
            }
        }

        consumeStats(
            realValue(obj, "stats")
        )

        consumeStats(
            realValue(obj, "statistics")
        )

        /*
         * También permitimos estadísticas directamente
         * en el objeto raíz.
         */
        obj.keys().forEachRemaining { key ->

            val raw = obj.opt(key)

            if (
                raw == null ||
                raw === JSONObject.NULL
            ) {
                return@forEachRemaining
            }

            val number = toIntOrNull(raw)

            if (number != null) {

                stats.putIfAbsent(
                    normalizeStatKey(key),
                    number
                )
            }
        }

        Log.d(
            TAG,
            "STATS ENCONTRADAS PARA $requestedName: $stats"
        )

        fun get(vararg keys: String): Int? {

            for (key in keys) {

                stats[
                    normalizeStatKey(key)
                ]?.let {
                    return it
                }
            }

            return null
        }

        val result = PlayerStats(

            playerName =
                findPlayerName(obj)
                    ?: requestedName,

            goals = get(
                "goals",
                "goal",
                "total_goals"
            ),

            assists = get(
                "goal_assists",
                "assists",
                "assist",
                "total_assists"
            ),

            yellowCards = get(
                "yellow_cards",
                "yellowcards",
                "total_yellow_cards"
            ),

            redCards = get(
                "red_cards",
                "redcards",
                "total_red_cards",
                "straight_red_cards",
                "red_cards_2nd_yellow",
                "second_yellow_red_card"
            ),

            cleanSheets = get(
                "clean_sheets",
                "cleansheets",
                "clean_sheet"
            ),

            source = "LALIGA"
        )

        Log.d(
            TAG,
            "RESULTADO FINAL: $result"
        )

        return result
    }

    // ============================================================
    // EXTRACCIÓN ROBUSTA DE ESTADÍSTICAS
    // ============================================================

    /**
     * Intenta localizar el nombre de la estadística.
     *
     * "stat" NO se usa directamente aquí porque en algunos
     * esquemas puede ser el VALOR numérico de la estadística.
     */
    private fun extractStatKey(
        obj: JSONObject
    ): String? {

        val possibleKeys = listOf(
            "name",
            "key",
            "stat_name",
            "statName",
            "type",
            "code"
        )

        for (key in possibleKeys) {

            val raw = realValue(
                obj,
                key
            ) ?: continue

            if (raw is String) {

                val value = raw.trim()

                if (
                    value.isNotBlank() &&
                    value.any(Char::isLetter)
                ) {
                    return value
                }
            }
        }

        /*
         * Como último recurso, "stat" puede ser el nombre
         * si realmente contiene texto.
         */
        val stat = realValue(
            obj,
            "stat"
        )

        if (
            stat is String &&
            stat.any(Char::isLetter)
        ) {
            return stat
        }

        return null
    }

    /**
     * Devuelve el primer valor REAL.
     *
     * JSONObject.NULL != null en Kotlin.
     *
     * Este era uno de los problemas de la implementación
     * anterior.
     */
    private fun firstRealValue(
        obj: JSONObject,
        vararg keys: String
    ): Any? {

        for (key in keys) {

            if (!obj.has(key)) {
                continue
            }

            val value = obj.opt(key)

            if (
                value != null &&
                value !== JSONObject.NULL
            ) {
                return value
            }
        }

        return null
    }

    private fun realValue(
        obj: JSONObject,
        key: String
    ): Any? {

        if (!obj.has(key)) {
            return null
        }

        val value = obj.opt(key)

        return if (
            value == null ||
            value === JSONObject.NULL
        ) {
            null
        } else {
            value
        }
    }

    private fun toIntOrNull(
        value: Any?
    ): Int? {

        return when (value) {

            is Byte ->
                value.toInt()

            is Short ->
                value.toInt()

            is Int ->
                value

            is Long ->
                value.toInt()

            is Float ->
                value.toInt()

            is Double ->
                value.toInt()

            is Number ->
                value.toInt()

            is String ->
                value
                    .replace(",", ".")
                    .trim()
                    .toDoubleOrNull()
                    ?.toInt()

            else ->
                null
        }
    }

    // ============================================================
    // HTTP / API
    // ============================================================

    private fun discoverApiKey(): String {

        val pages = listOf(
            "https://www.laliga.com/",
            "https://www.laliga.com/laliga-easports"
        )

        val regexes = listOf(

            Regex(
                "backendSubscription[\\\"']?\\s*[:=]\\s*[\\\"']" +
                    "([a-fA-F0-9]{24,64})"
            ),

            Regex(
                "Ocp-Apim-Subscription-Key[\\\"']?\\s*[:=]\\s*[\\\"']" +
                    "([a-fA-F0-9]{24,64})"
            )
        )

        for (page in pages) {

            val html = request(
                page,
                null
            )

            for (regex in regexes) {

                regex
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let {
                        Log.d(
                            TAG,
                            "API key detectada dinámicamente"
                        )

                        return it
                    }
            }
        }

        /*
         * Fallback actual.
         * Puede rotar en cualquier momento.
         */
        return "c13c3a8e2f6b46da9c5c425cf61fab3e"
    }

    private fun request(
        url: String,
        apiKey: String?
    ): String {

        val connection =
            URL(url).openConnection()
                as HttpURLConnection

        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"

        connection.setRequestProperty(
            "Accept",
            "application/json,text/html,*/*"
        )

        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android) FantasyCompanion/0.3"
        )

        if (apiKey != null) {

            connection.setRequestProperty(
                "Ocp-Apim-Subscription-Key",
                apiKey
            )
        }

        try {

            val code =
                connection.responseCode

            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val text =
                BufferedReader(
                    InputStreamReader(stream)
                ).use {
                    it.readText()
                }

            if (code !in 200..299) {

                Log.e(
                    TAG,
                    "HTTP $code -> $url"
                )

                error(
                    "LALIGA HTTP $code"
                )
            }

            return text

        } finally {

            connection.disconnect()
        }
    }

    // ============================================================
    // JSON
    // ============================================================

    private fun parseAny(
        text: String
    ): Any {

        return when {

            text
                .trimStart()
                .startsWith("[") ->
                JSONArray(text)

            else ->
                JSONObject(text)
        }
    }

    /**
     * Recorremos la respuesta para encontrar registros.
     *
     * Después filtramos estrictamente para aceptar únicamente
     * objetos que tengan stats/statistics.
     */
    private fun collectObjects(
        value: Any?
    ): List<JSONObject> {

        val out =
            mutableListOf<JSONObject>()

        fun walk(v: Any?) {

            when (v) {

                is JSONObject -> {

                    out += v

                    v.keys().forEachRemaining { key ->
                        walk(v.opt(key))
                    }
                }

                is JSONArray -> {

                    for (
                        i in 0 until v.length()
                    ) {
                        walk(v.opt(i))
                    }
                }
            }
        }

        walk(value)

        return out
    }

    // ============================================================
    // NORMALIZACIÓN
    // ============================================================

    private fun normalize(
        value: String
    ): String {

        return Normalizer
            .normalize(
                value.lowercase(),
                Normalizer.Form.NFD
            )
            .replace(
                Regex("\\p{M}+"),
                ""
            )
            .replace(
                Regex("[^a-z0-9 ]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun normalizeStatKey(
        value: String
    ): String {

        return normalize(value)
            .replace(
                ' ',
                '_'
            )
    }
}
