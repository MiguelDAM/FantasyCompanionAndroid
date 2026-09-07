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

class LaLigaStatsRepository {

    companion object {
        private const val TAG = "FantasyAPI"

        private const val PAGE_SIZE = 100
        private const val MAX_OFFSET = 1400

        private const val CONNECT_TIMEOUT = 6000
        private const val READ_TIMEOUT = 10000

        private const val FALLBACK_API_KEY =
            "c13c3a8e2f6b46da9c5c425cf61fab3e"

        private val SUBSCRIPTIONS = listOf(
            "laliga-easports-2026",
            "laliga-hypermotion-2026"
        )
    }

    private val main =
        Handler(Looper.getMainLooper())

    /*
     * Resultado final por nombre solicitado.
     */
    private val resultCache =
        ConcurrentHashMap<String, PlayerStats>()

    /*
     * Índice de jugadores ya descargados.
     *
     * alias normalizado -> JSONObject REAL de player_stats[]
     */
    private val playerIndex =
        ConcurrentHashMap<String, JSONObject>()

    /*
     * Páginas ya descargadas durante esta sesión.
     */
    private val loadedPages =
        ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var subscriptionKey: String? = null

    private val loadLock = Any()

    // ============================================================
    // API
    // ============================================================

    fun get(
        playerName: String,
        callback: (PlayerStats) -> Unit
    ) {

        val requestedKey =
            normalize(playerName)

        resultCache[requestedKey]?.let {
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

                findPlayerStats(
                    playerName
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "ERROR buscando $playerName",
                    e
                )

                PlayerStats(
                    playerName = playerName,
                    error = e.message
                        ?: "Error consultando LALIGA"
                )
            }

            if (
                result.error == null &&
                !result.loading
            ) {
                resultCache[requestedKey] =
                    result
            }

            main.post {
                callback(result)
            }
        }
    }

    // ============================================================
    // BÚSQUEDA
    // ============================================================

    private fun findPlayerStats(
        requestedName: String
    ): PlayerStats {

        val target =
            normalize(requestedName)

        Log.d(
            TAG,
            "=========================================="
        )

        Log.d(
            TAG,
            "BUSCANDO '$requestedName' -> '$target'"
        )

        /*
         * Primero memoria.
         */
        findBestPlayer(target)?.let {

            Log.d(
                TAG,
                "MATCH desde caché: ${playerDisplayName(it)}"
            )

            return parsePlayerStats(
                requestedName,
                it
            )
        }

        synchronized(loadLock) {

            /*
             * Puede haberlo cargado otro hilo.
             */
            findBestPlayer(target)?.let {

                return parsePlayerStats(
                    requestedName,
                    it
                )
            }

            val apiKey =
                getApiKey()

            var anySuccessfulRequest = false
            var lastNetworkError: String? = null

            for (
                subscription in SUBSCRIPTIONS
            ) {

                Log.d(
                    TAG,
                    "COMPETICIÓN: $subscription"
                )

                for (
                    offset in
                    0..MAX_OFFSET step PAGE_SIZE
                ) {

                    val pageId =
                        "$subscription:$offset"

                    /*
                     * Si ya tenemos la página,
                     * buscamos inmediatamente en memoria.
                     */
                    if (
                        loadedPages.contains(pageId)
                    ) {

                        findBestPlayer(target)?.let {

                            return parsePlayerStats(
                                requestedName,
                                it
                            )
                        }

                        continue
                    }

                    val url =
                        "https://apim.laliga.com/" +
                            "public-service/api/v1/" +
                            "subscriptions/$subscription/" +
                            "players/stats" +
                            "?limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    Log.d(
                        TAG,
                        "GET $pageId"
                    )

                    val body = try {

                        request(
                            url,
                            apiKey
                        )

                    } catch (e: Exception) {

                        lastNetworkError =
                            e.message

                        Log.w(
                            TAG,
                            "ERROR $pageId: ${e.message}"
                        )

                        /*
                         * No seguimos insistiendo en la misma liga
                         * si el endpoint está fallando.
                         */
                        break
                    }

                    anySuccessfulRequest = true

                    val root =
                        JSONObject(body)

                    /*
                     * ESTA ES LA PARTE IMPORTANTE.
                     *
                     * La respuesta REAL usa directamente:
                     *
                     * {
                     *   "player_stats": [...]
                     * }
                     */
                    val players =
                        root.optJSONArray(
                            "player_stats"
                        )

                    if (players == null) {

                        Log.e(
                            TAG,
                            "La respuesta NO contiene player_stats. " +
                                "Claves=${jsonKeys(root)}"
                        )

                        /*
                         * Dejamos de inventar estructuras.
                         */
                        break
                    }

                    Log.d(
                        TAG,
                        "$pageId devuelve ${players.length()} jugadores"
                    )

                    indexPlayers(
                        players
                    )

                    loadedPages.add(
                        pageId
                    )

                    /*
                     * Buscar inmediatamente tras cada página.
                     */
                    findBestPlayer(target)?.let {

                        Log.d(
                            TAG,
                            "MATCH después de $pageId: " +
                                playerDisplayName(it)
                        )

                        Log.d(
                            TAG,
                            "TEAM: ${teamName(it)}"
                        )

                        Log.d(
                            TAG,
                            "OPTA: ${it.optString("opta_id")}"
                        )

                        Log.d(
                            TAG,
                            "JSON: ${it.toString().take(3000)}"
                        )

                        return parsePlayerStats(
                            requestedName,
                            it
                        )
                    }

                    /*
                     * Fin real de paginación.
                     *
                     * El código de referencia también termina
                     * cuando llegan menos de 100 jugadores.
                     */
                    if (
                        players.length() < PAGE_SIZE
                    ) {

                        Log.d(
                            TAG,
                            "FIN $subscription en offset=$offset"
                        )

                        break
                    }
                }

                /*
                 * Comprobar una última vez antes de Segunda.
                 */
                findBestPlayer(target)?.let {

                    return parsePlayerStats(
                        requestedName,
                        it
                    )
                }
            }

            if (!anySuccessfulRequest) {

                return PlayerStats(
                    playerName = requestedName,
                    error =
                        lastNetworkError
                            ?: "LALIGA no responde"
                )
            }

            return PlayerStats(
                playerName = requestedName,
                error = "Jugador no encontrado"
            )
        }
    }

    // ============================================================
    // INDEXAR player_stats[]
    // ============================================================

    private fun indexPlayers(
        players: JSONArray
    ) {

        for (
            i in 0 until players.length()
        ) {

            val player =
                players.optJSONObject(i)
                    ?: continue

            /*
             * Ya sabemos que ESTE objeto sí representa un jugador.
             *
             * No usamos collectObjects(),
             * hasStatsContainer(),
             * ni búsquedas recursivas.
             */
            val aliases =
                linkedSetOf<String>()

            val name =
                player
                    .optString("name", "")
                    .trim()

            val nickname =
                player
                    .optString("nickname", "")
                    .trim()

            val fullName =
                player
                    .optString("full_name", "")
                    .trim()

            val displayName =
                player
                    .optString("display_name", "")
                    .trim()

            val playerName =
                player
                    .optString("player_name", "")
                    .trim()

            listOf(
                name,
                nickname,
                fullName,
                displayName,
                playerName
            )
                .filter {
                    it.length >= 3
                }
                .forEach {
                    aliases.add(it)
                }

            /*
             * Log útil mientras depuramos.
             */
            if (
                aliases.isNotEmpty()
            ) {

                Log.v(
                    TAG,
                    "INDEX: ${aliases.joinToString()} " +
                        "| ${teamName(player)} " +
                        "| ${player.optString("opta_id")}"
                )
            }

            aliases.forEach { alias ->

                playerIndex.putIfAbsent(
                    normalize(alias),
                    player
                )
            }
        }
    }

    // ============================================================
    // MATCH DE NOMBRE
    // ============================================================

    private fun findBestPlayer(
        target: String
    ): JSONObject? {

        /*
         * Exacto primero.
         */
        playerIndex[target]?.let {
            return it
        }

        var best:
            JSONObject? = null

        var bestScore = 0

        for (
            (alias, player)
            in playerIndex
        ) {

            val score =
                nameScore(
                    target,
                    alias
                )

            if (
                score > bestScore
            ) {

                bestScore = score
                best = player
            }
        }

        Log.d(
            TAG,
            "BEST SCORE para '$target' = $bestScore"
        )

        /*
         * No volvemos a permitir matches débiles.
         */
        return if (
            bestScore >= 75
        ) {
            best
        } else {
            null
        }
    }

    private fun nameScore(
        requested: String,
        candidate: String
    ): Int {

        if (
            requested.isBlank() ||
            candidate.isBlank()
        ) {
            return 0
        }

        if (
            requested == candidate
        ) {
            return 100
        }

        /*
         * Lewandowski
         * Robert Lewandowski
         */
        if (
            requested.length >= 5 &&
            candidate.length >= 5 &&
            (
                requested.contains(candidate) ||
                    candidate.contains(requested)
            )
        ) {
            return 90
        }

        val a =
            requested
                .split(" ")
                .filter {
                    it.length > 2
                }
                .toSet()

        val b =
            candidate
                .split(" ")
                .filter {
                    it.length > 2
                }
                .toSet()

        if (
            a.isEmpty() ||
            b.isEmpty()
        ) {
            return 0
        }

        val common =
            a.intersect(b)

        /*
         * Dos nombres largos:
         * necesitamos dos palabras comunes.
         */
        if (
            a.size >= 2 &&
            b.size >= 2
        ) {

            if (
                common.size >= 2
            ) {
                return 82
            }

            return 0
        }

        /*
         * Pedri, Gavi, Raphinha...
         */
        if (
            common.isNotEmpty()
        ) {
            return 75
        }

        return 0
    }

    // ============================================================
    // PARSEO DE STATS
    // ============================================================

    private fun parsePlayerStats(
        requestedName: String,
        player: JSONObject
    ): PlayerStats {

        val stats =
            mutableMapOf<String, Int>()

        /*
         * Estructura confirmada:
         *
         * "stats": [
         *   {
         *     "name": "goals",
         *     "stat": 3
         *   }
         * ]
         */
        val array =
            player.optJSONArray(
                "stats"
            )

        if (
            array == null
        ) {

            Log.w(
                TAG,
                "Jugador encontrado SIN stats[]: " +
                    playerDisplayName(player)
            )

        } else {

            for (
                i in 0 until array.length()
            ) {

                val item =
                    array.optJSONObject(i)
                        ?: continue

                val name =
                    item
                        .optString(
                            "name",
                            ""
                        )
                        .trim()

                if (
                    name.isBlank()
                ) {
                    continue
                }

                val raw =
                    item.opt("stat")

                val number =
                    when (raw) {

                        is Number ->
                            raw.toInt()

                        is String ->
                            raw
                                .replace(",", ".")
                                .toDoubleOrNull()
                                ?.toInt()

                        else ->
                            null
                    }

                if (
                    number != null
                ) {

                    stats[
                        normalizeStatKey(
                            name
                        )
                    ] = number
                }
            }
        }

        Log.d(
            TAG,
            "PLAYER=${playerDisplayName(player)}"
        )

        Log.d(
            TAG,
            "TEAM=${teamName(player)}"
        )

        Log.d(
            TAG,
            "OPTA=${player.optString("opta_id")}"
        )

        Log.d(
            TAG,
            "STATS=$stats"
        )

        fun get(
            vararg names: String
        ): Int? {

            for (
                name in names
            ) {

                stats[
                    normalizeStatKey(
                        name
                    )
                ]?.let {
                    return it
                }
            }

            return null
        }

        return PlayerStats(

            playerName =
                playerDisplayName(player)
                    .ifBlank {
                        requestedName
                    },

            goals = get(
                "goals"
            ),

            assists = get(
                "goal_assists",
                "assists"
            ),

            yellowCards = get(
                "yellow_cards",
                "yellowcards"
            ),

            redCards = get(
                "total_red_cards",
                "straight_red_cards",
                "red_cards",
                "red_cards_2nd_yellow",
                "second_yellow_red_card"
            ),

            cleanSheets = get(
                "clean_sheets"
            ),

            source = "LALIGA"
        )
    }

    // ============================================================
    // DATOS BÁSICOS
    // ============================================================

    private fun playerDisplayName(
        player: JSONObject
    ): String {

        val name =
            player
                .optString(
                    "name",
                    ""
                )
                .trim()

        if (
            name.isNotBlank()
        ) {
            return name
        }

        val nickname =
            player
                .optString(
                    "nickname",
                    ""
                )
                .trim()

        if (
            nickname.isNotBlank()
        ) {
            return nickname
        }

        return ""
    }

    private fun teamName(
        player: JSONObject
    ): String {

        val team =
            player.optJSONObject(
                "team"
            )
                ?: return ""

        val nickname =
            team
                .optString(
                    "nickname",
                    ""
                )
                .trim()

        if (
            nickname.isNotBlank()
        ) {
            return nickname
        }

        return team
            .optString(
                "name",
                ""
            )
            .trim()
    }

    // ============================================================
    // API KEY
    // ============================================================

    private fun getApiKey(): String {

        subscriptionKey?.let {
            return it
        }

        /*
         * Para esta build de depuración no perdemos tiempo
         * consultando laliga.com antes de cada arranque.
         *
         * Usamos directamente la key pública conocida.
         */
        subscriptionKey =
            FALLBACK_API_KEY

        return FALLBACK_API_KEY
    }

    // ============================================================
    // HTTP
    // ============================================================

    private fun request(
        url: String,
        apiKey: String
    ): String {

        var lastError:
            Exception? = null

        /*
         * Dos intentos máximo.
         */
        repeat(2) {

            try {

                return requestOnce(
                    url,
                    apiKey
                )

            } catch (e: Exception) {

                lastError = e

                Log.w(
                    TAG,
                    "HTTP intento ${it + 1}/2: ${e.message}"
                )

                try {
                    Thread.sleep(300)
                } catch (_: Exception) {
                }
            }
        }

        throw lastError
            ?: IllegalStateException(
                "Error consultando LALIGA"
            )
    }

    private fun requestOnce(
        url: String,
        apiKey: String
    ): String {

        val connection =
            URL(url)
                .openConnection()
                as HttpURLConnection

        connection.connectTimeout =
            CONNECT_TIMEOUT

        connection.readTimeout =
            READ_TIMEOUT

        connection.requestMethod =
            "GET"

        connection.setRequestProperty(
            "Accept",
            "application/json"
        )

        connection.setRequestProperty(
            "Ocp-Apim-Subscription-Key",
            apiKey
        )

        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android) FantasyCompanion/0.5"
        )

        try {

            val code =
                connection.responseCode

            val stream =
                if (
                    code in 200..299
                ) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val body =
                if (
                    stream != null
                ) {

                    BufferedReader(
                        InputStreamReader(
                            stream
                        )
                    ).use {
                        it.readText()
                    }

                } else {
                    ""
                }

            if (
                code !in 200..299
            ) {

                throw IllegalStateException(
                    "LALIGA HTTP $code"
                )
            }

            return body

        } finally {

            connection.disconnect()
        }
    }

    // ============================================================
    // UTILIDADES
    // ============================================================

    private fun jsonKeys(
        obj: JSONObject
    ): String {

        val keys =
            mutableListOf<String>()

        obj.keys()
            .forEachRemaining {
                keys.add(it)
            }

        return keys.joinToString()
    }

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
