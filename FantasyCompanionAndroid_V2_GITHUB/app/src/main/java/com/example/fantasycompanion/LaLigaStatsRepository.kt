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

        private const val BASE_URL =
            "https://apim.laliga.com/public-service"

        private const val FALLBACK_API_KEY =
            "c13c3a8e2f6b46da9c5c425cf61fab3e"

        private const val CONNECT_TIMEOUT = 7000
        private const val READ_TIMEOUT = 12000
        private const val PAGE_SIZE = 100
    }

    private val main =
        Handler(Looper.getMainLooper())

    private val resultCache =
        ConcurrentHashMap<String, PlayerStats>()

    private val playerIndex =
        ConcurrentHashMap<String, JSONObject>()

    private val loadedSubscriptions =
        ConcurrentHashMap.newKeySet<String>()

    private val loadLock = Any()

    @Volatile
    private var apiKey: String =
        FALLBACK_API_KEY

    @Volatile
    private var discoveredSubscriptions:
        List<String>? = null

    // ============================================================
    // ENTRADA
    // ============================================================

    fun get(
        playerName: String,
        callback: (PlayerStats) -> Unit
    ) {

        val key =
            normalize(playerName)

        resultCache[key]?.let {
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

                findStats(
                    playerName
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "ERROR FINAL buscando $playerName",
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
                resultCache[key] =
                    result
            }

            main.post {
                callback(result)
            }
        }
    }

    // ============================================================
    // FLUJO PRINCIPAL
    // ============================================================

    private fun findStats(
        requestedName: String
    ): PlayerStats {

        val target =
            normalize(requestedName)

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "BUSCANDO: $requestedName"
        )

        /*
         * 1. Primero caché local.
         */
        findBestPlayer(target)?.let {

            return getIndividualStats(
                requestedName,
                it
            )
        }

        synchronized(loadLock) {

            findBestPlayer(target)?.let {

                return getIndividualStats(
                    requestedName,
                    it
                )
            }

            /*
             * 2. Descubrir temporadas reales.
             */
            val subscriptions =
                discoverCurrentSubscriptions()

            if (
                subscriptions.isEmpty()
            ) {

                return PlayerStats(
                    playerName = requestedName,
                    error =
                        "No se encontró la temporada 2026/27 en la API"
                )
            }

            Log.d(
                TAG,
                "SUBSCRIPTIONS 2026/27: $subscriptions"
            )

            /*
             * 3. Cargar jugadores.
             */
            for (
                subscription in subscriptions
            ) {

                if (
                    !loadedSubscriptions.contains(
                        subscription
                    )
                ) {

                    try {

                        loadPlayers(
                            subscription
                        )

                        loadedSubscriptions.add(
                            subscription
                        )

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "ERROR cargando $subscription",
                            e
                        )

                        /*
                         * Probamos la otra competición.
                         */
                        continue
                    }
                }

                /*
                 * 4. Buscar tras cargar esa competición.
                 */
                findBestPlayer(target)?.let {

                    Log.d(
                        TAG,
                        "JUGADOR LOCALIZADO: " +
                            "${it.optString("name")} " +
                            "slug=${it.optString("slug")} " +
                            "team=${teamName(it)}"
                    )

                    /*
                     * 5. Endpoint INDIVIDUAL.
                     */
                    return getIndividualStats(
                        requestedName,
                        it
                    )
                }
            }

            return PlayerStats(
                playerName = requestedName,
                error = "Jugador no encontrado"
            )
        }
    }

    // ============================================================
    // DESCUBRIR TEMPORADA
    // ============================================================

    private fun discoverCurrentSubscriptions():
        List<String> {

        discoveredSubscriptions?.let {
            return it
        }

        val found =
            mutableListOf<String>()

        /*
         * Hacemos varias páginas porque /subscriptions
         * está paginado.
         */
        for (
            offset in
            0..100 step 20
        ) {

            val url =
                "$BASE_URL/api/v1/subscriptions" +
                    "?limit=20&offset=$offset"

            val response =
                requestJson(
                    url
                )

            Log.d(
                TAG,
                "SUBSCRIPTIONS HTTP OK offset=$offset"
            )

            val subscriptions =
                response.optJSONArray(
                    "subscriptions"
                )
                    ?: continue

            for (
                i in 0 until subscriptions.length()
            ) {

                val item =
                    subscriptions.optJSONObject(i)
                        ?: continue

                val slug =
                    item.optString(
                        "slug",
                        ""
                    )

                val year =
                    item
                        .opt("year")
                        ?.toString()
                        .orEmpty()

                Log.v(
                    TAG,
                    "SUB: slug=$slug year=$year"
                )

                /*
                 * Preferimos explícitamente 2026.
                 */
                if (
                    slug == "laliga-easports-2026" ||
                    slug == "laliga-hypermotion-2026"
                ) {

                    found.add(
                        slug
                    )
                }
                /*
                 * Fallback si cambia ligeramente naming
                 * pero sigue siendo temporada 2026.
                 */
                else if (
                    year == "2026" &&
                    (
                        slug.contains(
                            "laliga-easports"
                        ) ||
                        slug.contains(
                            "laliga-hypermotion"
                        )
                    )
                ) {

                    found.add(
                        slug
                    )
                }
            }

            if (
                found.size >= 2
            ) {
                break
            }
        }

        /*
         * Si /subscriptions no nos devuelve algo útil,
         * todavía probamos los slugs esperados.
         */
        if (
            found.isEmpty()
        ) {

            Log.w(
                TAG,
                "No se descubrieron subscriptions. Usando fallback."
            )

            found.add(
                "laliga-easports-2026"
            )

            found.add(
                "laliga-hypermotion-2026"
            )
        }

        val result =
            found.distinct()

        discoveredSubscriptions =
            result

        return result
    }

    // ============================================================
    // CARGAR LISTADO DE JUGADORES
    // ============================================================

    private fun loadPlayers(
        subscription: String
    ) {

        Log.d(
            TAG,
            "CARGANDO JUGADORES: $subscription"
        )

        var offset = 0

        while (
            offset < 1500
        ) {

            val url =
                "$BASE_URL/api/v1/" +
                    "subscriptions/$subscription/" +
                    "players/stats" +
                    "?limit=$PAGE_SIZE" +
                    "&offset=$offset"

            val root =
                requestJson(
                    url
                )

            val total =
                root.optInt(
                    "total",
                    -1
                )

            val players =
                root.optJSONArray(
                    "player_stats"
                )

            if (
                players == null
            ) {

                throw IllegalStateException(
                    "Respuesta sin player_stats " +
                        "(keys=${jsonKeys(root)})"
                )
            }

            Log.d(
                TAG,
                "$subscription offset=$offset " +
                    "total=$total " +
                    "recibidos=${players.length()}"
            )

            if (
                players.length() == 0
            ) {
                break
            }

            indexPlayers(
                players
            )

            if (
                players.length() < PAGE_SIZE
            ) {
                break
            }

            offset +=
                PAGE_SIZE
        }

        Log.d(
            TAG,
            "ÍNDICE TOTAL: ${playerIndex.size} aliases"
        )
    }

    // ============================================================
    // INDEX
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

            val aliases =
                linkedSetOf<String>()

            addAlias(
                aliases,
                player.optString(
                    "name",
                    ""
                )
            )

            addAlias(
                aliases,
                player.optString(
                    "nickname",
                    ""
                )
            )

            addAlias(
                aliases,
                player.optString(
                    "full_name",
                    ""
                )
            )

            addAlias(
                aliases,
                player.optString(
                    "display_name",
                    ""
                )
            )

            addAlias(
                aliases,
                player.optString(
                    "player_name",
                    ""
                )
            )

            /*
             * También aprovechamos el slug.
             *
             * Ejemplo:
             * lamine-yamal -> lamine yamal
             */
            val slug =
                player.optString(
                    "slug",
                    ""
                )

            if (
                slug.isNotBlank()
            ) {

                addAlias(
                    aliases,
                    slug.replace(
                        '-',
                        ' '
                    )
                )
            }

            for (
                alias in aliases
            ) {

                playerIndex.putIfAbsent(
                    normalize(alias),
                    player
                )
            }
        }
    }

    private fun addAlias(
        aliases:
            MutableSet<String>,
        value: String
    ) {

        val clean =
            value.trim()

        if (
            clean.length >= 3 &&
            clean.any(
                Char::isLetter
            )
        ) {

            aliases.add(
                clean
            )
        }
    }

    // ============================================================
    // BUSCAR MEJOR JUGADOR
    // ============================================================

    private fun findBestPlayer(
        requested: String
    ): JSONObject? {

        playerIndex[
            requested
        ]?.let {

            Log.d(
                TAG,
                "MATCH EXACTO: $requested"
            )

            return it
        }

        var best:
            JSONObject? = null

        var bestAlias = ""
        var bestScore = 0

        for (
            (alias, player)
            in playerIndex
        ) {

            val score =
                scoreName(
                    requested,
                    alias
                )

            if (
                score > bestScore
            ) {

                bestScore =
                    score

                bestAlias =
                    alias

                best =
                    player
            }
        }

        Log.d(
            TAG,
            "BEST '$requested' -> " +
                "'$bestAlias' score=$bestScore"
        )

        return if (
            bestScore >= 72
        ) {
            best
        } else {
            null
        }
    }

    private fun scoreName(
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

        if (
            requested.length >= 4 &&
            candidate.length >= 4 &&
            (
                requested.contains(
                    candidate
                ) ||
                candidate.contains(
                    requested
                )
            )
        ) {
            return 90
        }

        val a =
            requested
                .split(' ')
                .filter {
                    it.length > 2
                }
                .toSet()

        val b =
            candidate
                .split(' ')
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
            a.intersect(
                b
            )

        if (
            common.size >= 2
        ) {
            return 82
        }

        /*
         * Jugadores de nombre único:
         * Pedri, Gavi, Raphinha...
         */
        if (
            common.size == 1 &&
            (
                a.size == 1 ||
                b.size == 1
            )
        ) {
            return 75
        }

        return 0
    }

    // ============================================================
    // ENDPOINT INDIVIDUAL
    // ============================================================

    private fun getIndividualStats(
        requestedName: String,
        indexedPlayer: JSONObject
    ): PlayerStats {

        val slug =
            indexedPlayer
                .optString(
                    "slug",
                    ""
                )
                .trim()

        /*
         * Si no hubiera slug usamos directamente
         * el objeto del listado.
         */
        if (
            slug.isBlank()
        ) {

            Log.w(
                TAG,
                "Jugador sin slug. Usando stats del listado."
            )

            return parsePlayerStats(
                requestedName,
                indexedPlayer
            )
        }

        val url =
            "$BASE_URL/api/v1/players/" +
                "$slug/stats"

        Log.d(
            TAG,
            "GET PLAYER STATS: $url"
        )

        return try {

            val root =
                requestJson(
                    url
                )

            val player =
                root.optJSONObject(
                    "player_stats"
                )

            if (
                player == null
            ) {

                Log.w(
                    TAG,
                    "Endpoint individual sin player_stats. " +
                        "Usando objeto del listado."
                )

                parsePlayerStats(
                    requestedName,
                    indexedPlayer
                )

            } else {

                Log.d(
                    TAG,
                    "INDIVIDUAL OK: " +
                        "${player.optString("name")} " +
                        "team=${teamName(player)} " +
                        "slug=${player.optString("slug")}"
                )

                parsePlayerStats(
                    requestedName,
                    player
                )
            }

        } catch (e: Exception) {

            /*
             * Si el endpoint individual falla,
             * el listado YA contiene stats[].
             */
            Log.w(
                TAG,
                "Individual falló (${e.message}). " +
                    "Usando stats del listado."
            )

            parsePlayerStats(
                requestedName,
                indexedPlayer
            )
        }
    }

    // ============================================================
    // PARSEO FINAL
    // ============================================================

    private fun parsePlayerStats(
        requestedName: String,
        player: JSONObject
    ): PlayerStats {

        val map =
            mutableMapOf<String, Int>()

        val stats =
            player.optJSONArray(
                "stats"
            )

        if (
            stats == null
        ) {

            return PlayerStats(
                playerName =
                    player.optString(
                        "name",
                        requestedName
                    ),
                error = "Jugador encontrado pero sin stats[]"
            )
        }

        for (
            i in 0 until stats.length()
        ) {

            val stat =
                stats.optJSONObject(i)
                    ?: continue

            val name =
                stat
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
                stat.opt(
                    "stat"
                )

            val value =
                when (raw) {

                    is Number ->
                        raw.toInt()

                    is String ->
                        raw
                            .replace(
                                ",",
                                "."
                            )
                            .toDoubleOrNull()
                            ?.toInt()

                    else ->
                        null
                }

            if (
                value != null
            ) {

                map[
                    normalizeStatKey(
                        name
                    )
                ] = value
            }
        }

        Log.d(
            TAG,
            "PLAYER=${player.optString("name")}"
        )

        Log.d(
            TAG,
            "TEAM=${teamName(player)}"
        )

        Log.d(
            TAG,
            "SLUG=${player.optString("slug")}"
        )

        Log.d(
            TAG,
            "OPTA=${player.optString("opta_id")}"
        )

        Log.d(
            TAG,
            "STATS=$map"
        )

        fun stat(
            vararg names: String
        ): Int? {

            for (
                name in names
            ) {

                map[
                    normalizeStatKey(
                        name
                    )
                ]?.let {
                    return it
                }
            }

            return null
        }

        /*
         * Tarjetas rojas:
         *
         * preferimos total_red_cards.
         *
         * Si no existe, sumamos roja directa +
         * segunda amarilla cuando ambos campos existen.
         */
        val totalRed =
            stat(
                "total_red_cards",
                "red_cards"
            )

        val straightRed =
            stat(
                "straight_red_cards"
            )

        val secondYellowRed =
            stat(
                "red_cards_2nd_yellow",
                "second_yellow_red_card"
            )

        val redCards =
            totalRed
                ?: if (
                    straightRed != null ||
                    secondYellowRed != null
                ) {

                    (straightRed ?: 0) +
                        (secondYellowRed ?: 0)

                } else {

                    null
                }

        val result =
            PlayerStats(

                playerName =
                    player
                        .optString(
                            "name",
                            requestedName
                        )
                        .ifBlank {
                            requestedName
                        },

                goals =
                    stat(
                        "goals"
                    ),

                assists =
                    stat(
                        "goal_assists",
                        "assists"
                    ),

                yellowCards =
                    stat(
                        "yellow_cards"
                    ),

                redCards =
                    redCards,

                cleanSheets =
                    stat(
                        "clean_sheets"
                    ),

                source =
                    "LALIGA"
            )

        Log.d(
            TAG,
            "RESULT=$result"
        )

        return result
    }

    // ============================================================
    // HTTP = NUESTRO "POSTMAN"
    // ============================================================

    private fun requestJson(
        url: String
    ): JSONObject {

        var lastException:
            Exception? = null

        repeat(2) { attempt ->

            try {

                return requestJsonOnce(
                    url
                )

            } catch (e: Exception) {

                lastException =
                    e

                Log.w(
                    TAG,
                    "HTTP intento ${attempt + 1}/2 " +
                        "URL=$url " +
                        "ERROR=${e.message}"
                )

                if (
                    attempt == 0
                ) {

                    try {
                        Thread.sleep(
                            400
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }

        throw lastException
            ?: IllegalStateException(
                "Error HTTP desconocido"
            )
    }

    private fun requestJsonOnce(
        url: String
    ): JSONObject {

        val connection =
            URL(url)
                .openConnection()
                as HttpURLConnection

        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            CONNECT_TIMEOUT

        connection.readTimeout =
            READ_TIMEOUT

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
            "Mozilla/5.0 (Android) FantasyCompanion/0.6"
        )

        try {

            val code =
                connection.responseCode

            val contentType =
                connection.contentType
                    ?: "?"

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

            /*
             * ESTA PARTE ES EL TEST POSTMAN.
             */
            Log.d(
                TAG,
                "HTTP $code"
            )

            Log.d(
                TAG,
                "URL=$url"
            )

            Log.d(
                TAG,
                "CONTENT-TYPE=$contentType"
            )

            Log.d(
                TAG,
                "BODY=${body.take(1500)}"
            )

            if (
                code !in 200..299
            ) {

                throw IllegalStateException(
                    "LALIGA HTTP $code"
                )
            }

            if (
                body.isBlank()
            ) {

                throw IllegalStateException(
                    "LALIGA respondió vacío"
                )
            }

            return JSONObject(
                body
            )

        } finally {

            connection.disconnect()
        }
    }

    // ============================================================
    // UTILIDADES
    // ============================================================

    private fun teamName(
        player: JSONObject
    ): String {

        val team =
            player.optJSONObject(
                "team"
            )
                ?: return ""

        return team
            .optString(
                "nickname",
                team.optString(
                    "name",
                    ""
                )
            )
    }

    private fun jsonKeys(
        obj: JSONObject
    ): String {

        val result =
            mutableListOf<String>()

        obj.keys()
            .forEachRemaining {
                result.add(
                    it
                )
            }

        return result.joinToString()
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

        return normalize(
            value
        ).replace(
            ' ',
            '_'
        )
    }
}
