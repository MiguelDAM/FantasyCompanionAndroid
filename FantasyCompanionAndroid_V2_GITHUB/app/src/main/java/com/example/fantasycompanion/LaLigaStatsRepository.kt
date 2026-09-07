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

        private const val API_KEY =
            "c13c3a8e2f6b46da9c5c425cf61fab3e"

        private const val PAGE_SIZE = 100

        private const val CONNECT_TIMEOUT = 7000
        private const val READ_TIMEOUT = 12000

        private val SUBSCRIPTIONS = listOf(
            "laliga-easports-2026",
            "laliga-hypermotion-2026"
        )
    }

    private val main =
        Handler(Looper.getMainLooper())

    /*
     * alias normalizado -> jugador LALIGA
     */
    private val playerIndex =
        ConcurrentHashMap<String, JSONObject>()

    /*
     * Nombre real del jugador -> resultado final.
     */
    private val statsCache =
        ConcurrentHashMap<String, PlayerStats>()

    private val loadedSubscriptions =
        ConcurrentHashMap.newKeySet<String>()

    private val loadLock = Any()

    // ============================================================
    // COMPATIBILIDAD
    // ============================================================

    fun get(
        playerName: String,
        callback: (PlayerStats) -> Unit
    ) {

        get(
            candidateNames = listOf(playerName),
            callback = callback
        )
    }

    // ============================================================
    // NUEVO MÉTODO
    //
    // Recibe VARIOS textos que podrían ser el jugador.
    //
    // Ejemplo:
    //
    // FC Barcelona
    // Lamine Yamal
    // Delantero
    //
    // El índice de LALIGA determinará cuál es realmente un jugador.
    // ============================================================

    fun get(
        candidateNames: List<String>,
        callback: (PlayerStats) -> Unit
    ) {

        val candidates =
            candidateNames
                .map { it.trim() }
                .filter { it.length >= 3 }
                .distinctBy { normalize(it) }
                .take(8)

        if (candidates.isEmpty()) {

            callback(
                PlayerStats(
                    playerName = "Jugador",
                    error = "No se detectó ningún candidato"
                )
            )

            return
        }

        /*
         * IMPORTANTE:
         *
         * Ya no mostramos el primer candidato como jugador mientras
         * cargamos, porque puede ser precisamente el equipo.
         */
        callback(
            PlayerStats(
                playerName = "Identificando jugador…",
                loading = true
            )
        )

        thread(
            name = "laliga-stats",
            isDaemon = true
        ) {

            val result = try {

                findStatsForCandidates(
                    candidates
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error consultando candidatos $candidates",
                    e
                )

                PlayerStats(
                    playerName = candidates.first(),
                    error = e.message
                        ?: "Error consultando LALIGA"
                )
            }

            main.post {

                callback(
                    result
                )
            }
        }
    }

    // ============================================================
    // FLUJO PRINCIPAL
    // ============================================================

    private fun findStatsForCandidates(
        candidates: List<String>
    ): PlayerStats {

        Log.d(
            TAG,
            "========================================="
        )

        Log.d(
            TAG,
            "CANDIDATOS UI: ${candidates.joinToString(" | ")}"
        )

        /*
         * 1. Primero intentamos resolverlos con lo que ya tenemos
         * descargado.
         */
        findBestAcrossCandidates(
            candidates
        )?.let { match ->

            return resultForMatch(
                match
            )
        }

        synchronized(loadLock) {

            /*
             * Otro hilo podría haber cargado jugadores.
             */
            findBestAcrossCandidates(
                candidates
            )?.let { match ->

                return resultForMatch(
                    match
                )
            }

            var successfulRequests = 0
            var lastError: String? = null

            /*
             * Primera y Segunda División.
             */
            for (
                subscription in SUBSCRIPTIONS
            ) {

                if (
                    !loadedSubscriptions.contains(
                        subscription
                    )
                ) {

                    try {

                        loadSubscription(
                            subscription
                        )

                        loadedSubscriptions.add(
                            subscription
                        )

                        successfulRequests++

                    } catch (e: Exception) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Error cargando $subscription",
                            e
                        )

                        continue
                    }
                }

                /*
                 * Buscar tras cargar cada competición.
                 */
                findBestAcrossCandidates(
                    candidates
                )?.let { match ->

                    return resultForMatch(
                        match
                    )
                }
            }

            /*
             * Diagnóstico visible.
             */
            val diagnostic =
                bestDiagnostic(
                    candidates
                )

            if (
                successfulRequests == 0 &&
                playerIndex.isEmpty()
            ) {

                return PlayerStats(
                    playerName = candidates.first(),
                    error = buildString {

                        append(
                            lastError
                                ?: "No se pudieron cargar jugadores de LALIGA"
                        )

                        append("\nCandidatos: ")

                        append(
                            candidates.joinToString(" | ")
                        )

                        append(
                            "\nÍndice API: ${playerIndex.size}"
                        )
                    }
                )
            }

            return PlayerStats(
                playerName = candidates.first(),
                error = buildString {

                    append("Jugador no encontrado")

                    append(
                        "\nCandidatos: ${candidates.joinToString(" | ")}"
                    )

                    append(
                        "\nMejor API: ${diagnostic.alias.ifBlank { "—" }}"
                    )

                    append(
                        "\nScore: ${diagnostic.score}"
                    )

                    append(
                        "\nÍndice API: ${playerIndex.size}"
                    )
                }
            )
        }
    }

    // ============================================================
    // RESULTADO DEL MATCH
    // ============================================================

    private fun resultForMatch(
        match: PlayerMatch
    ): PlayerStats {

        val player =
            match.player

        val realName =
            playerDisplayName(
                player
            )

        val cacheKey =
            normalize(
                realName
            )

        statsCache[
            cacheKey
        ]?.let {

            return it
        }

        Log.d(
            TAG,
            "MATCH DEFINITIVO"
        )

        Log.d(
            TAG,
            "UI='${match.uiCandidate}'"
        )

        Log.d(
            TAG,
            "API='${match.apiAlias}'"
        )

        Log.d(
            TAG,
            "SCORE=${match.score}"
        )

        Log.d(
            TAG,
            "TEAM=${teamName(player)}"
        )

        Log.d(
            TAG,
            "SLUG=${player.optString("slug")}"
        )

        /*
         * Primero intentamos endpoint individual.
         *
         * Si falla, usamos stats[] que ya venían en el listado.
         */
        val result =
            fetchIndividualStats(
                player
            ) ?: parsePlayerStats(
                player
            )

        if (
            result.error == null
        ) {

            statsCache[
                normalize(result.playerName)
            ] = result
        }

        return result
    }

    // ============================================================
    // CARGA DE UNA COMPETICIÓN
    // ============================================================

    private fun loadSubscription(
        subscription: String
    ) {

        Log.d(
            TAG,
            "CARGANDO $subscription"
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

            val total =
                root.optInt(
                    "total",
                    -1
                )

            Log.d(
                TAG,
                "$subscription " +
                    "offset=$offset " +
                    "recibidos=${players.length()} " +
                    "total=$total"
            )

            if (
                players.length() == 0
            ) {
                break
            }

            indexPlayers(
                players
            )

            /*
             * Fin normal de paginación.
             */
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
            "ÍNDICE ACTUAL=${playerIndex.size}"
        )
    }

    // ============================================================
    // INDEXAR JUGADORES
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
             * El slug puede ayudar:
             *
             * lamine-yamal
             *      ↓
             * lamine yamal
             */
            val slug =
                player
                    .optString(
                        "slug",
                        ""
                    )
                    .trim()

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

            aliases.forEach { alias ->

                val normalized =
                    normalize(
                        alias
                    )

                if (
                    normalized.length >= 3
                ) {

                    playerIndex.putIfAbsent(
                        normalized,
                        player
                    )
                }
            }
        }
    }

    private fun addAlias(
        aliases: MutableSet<String>,
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
    // BUSCAR ENTRE TODOS LOS CANDIDATOS
    // ============================================================

    private data class PlayerMatch(

        val uiCandidate: String,

        val apiAlias: String,

        val player: JSONObject,

        val score: Int
    )

    private data class Diagnostic(

        val alias: String,

        val score: Int
    )

    private fun findBestAcrossCandidates(
        candidates: List<String>
    ): PlayerMatch? {

        var best:
            PlayerMatch? = null

        for (
            uiCandidate in candidates
        ) {

            val requested =
                normalize(
                    uiCandidate
                )

            /*
             * MATCH EXACTO.
             */
            playerIndex[
                requested
            ]?.let { player ->

                Log.d(
                    TAG,
                    "MATCH EXACTO: '$uiCandidate'"
                )

                return PlayerMatch(
                    uiCandidate =
                        uiCandidate,

                    apiAlias =
                        requested,

                    player =
                        player,

                    score =
                        100
                )
            }

            /*
             * MATCH APROXIMADO.
             */
            for (
                (apiAlias, player)
                in playerIndex
            ) {

                val score =
                    scoreName(
                        requested,
                        apiAlias
                    )

                if (
                    score >
                    (best?.score ?: 0)
                ) {

                    best =
                        PlayerMatch(
                            uiCandidate =
                                uiCandidate,

                            apiAlias =
                                apiAlias,

                            player =
                                player,

                            score =
                                score
                        )
                }
            }
        }

        if (
            best != null
        ) {

            Log.d(
                TAG,
                "BEST GLOBAL: " +
                    "UI='${best.uiCandidate}' " +
                    "API='${best.apiAlias}' " +
                    "score=${best.score}"
            )
        }

        /*
         * Seguimos siendo conservadores para no volver
         * al problema Barça/Espanyol.
         */
        return best
            ?.takeIf {
                it.score >= 75
            }
    }

    private fun bestDiagnostic(
        candidates: List<String>
    ): Diagnostic {

        var bestAlias = ""
        var bestScore = 0

        for (
            candidate in candidates
        ) {

            val requested =
                normalize(
                    candidate
                )

            for (
                apiAlias in playerIndex.keys
            ) {

                val score =
                    scoreName(
                        requested,
                        apiAlias
                    )

                if (
                    score > bestScore
                ) {

                    bestScore =
                        score

                    bestAlias =
                        apiAlias
                }
            }
        }

        return Diagnostic(
            alias =
                bestAlias,

            score =
                bestScore
        )
    }

    // ============================================================
    // COMPARAR NOMBRES
    // ============================================================

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

        /*
         * Pedri == Pedri
         */
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

        /*
         * Lamine Yamal
         * Lamine Yamal Nasraoui Ebana
         */
        if (
            common.size >= 2
        ) {
            return 82
        }

        /*
         * Nombre único:
         *
         * Pedri
         * Gavi
         * Raphinha
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

    private fun fetchIndividualStats(
        indexedPlayer: JSONObject
    ): PlayerStats? {

        val slug =
            indexedPlayer
                .optString(
                    "slug",
                    ""
                )
                .trim()

        if (
            slug.isBlank()
        ) {

            return null
        }

        val url =
            "$BASE_URL/api/v1/" +
                "players/$slug/stats"

        Log.d(
            TAG,
            "GET INDIVIDUAL $url"
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
                    ?: return null

            parsePlayerStats(
                player
            )

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Endpoint individual falló: ${e.message}"
            )

            null
        }
    }

    // ============================================================
    // PARSEO DE ESTADÍSTICAS
    // ============================================================

    private fun parsePlayerStats(
        player: JSONObject
    ): PlayerStats {

        val stats =
            mutableMapOf<String, Int>()

        val array =
            player.optJSONArray(
                "stats"
            )

        if (
            array == null
        ) {

            return PlayerStats(
                playerName =
                    playerDisplayName(
                        player
                    ),
                error =
                    "Jugador encontrado pero sin stats[]"
            )
        }

        for (
            i in 0 until array.length()
        ) {

            val item =
                array.optJSONObject(i)
                    ?: continue

            val statName =
                item
                    .optString(
                        "name",
                        ""
                    )
                    .trim()

            if (
                statName.isBlank()
            ) {
                continue
            }

            val raw =
                item.opt(
                    "stat"
                )

            val value =
                when (
                    raw
                ) {

                    is Number ->
                        raw.toInt()

                    is String ->
                        raw
                            .replace(
                                ",",
                                "."
                            )
                            .trim()
                            .toDoubleOrNull()
                            ?.toInt()

                    else ->
                        null
                }

            if (
                value != null
            ) {

                stats[
                    normalizeStatKey(
                        statName
                    )
                ] = value
            }
        }

        fun stat(
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
                    playerDisplayName(
                        player
                    ),

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
            "RESULTADO=$result"
        )

        Log.d(
            TAG,
            "TEAM=${teamName(player)}"
        )

        Log.d(
            TAG,
            "STATS=$stats"
        )

        return result
    }

    // ============================================================
    // HTTP
    // ============================================================

    private fun requestJson(
        url: String
    ): JSONObject {

        var lastError:
            Exception? = null

        repeat(2) { attempt ->

            try {

                return requestJsonOnce(
                    url
                )

            } catch (
                e: Exception
            ) {

                lastError =
                    e

                Log.w(
                    TAG,
                    "HTTP ${attempt + 1}/2: ${e.message}"
                )

                if (
                    attempt == 0
                ) {

                    try {

                        Thread.sleep(
                            350
                        )

                    } catch (
                        _: Exception
                    ) {
                    }
                }
            }
        }

        throw lastError
            ?: IllegalStateException(
                "Error HTTP"
            )
    }

    private fun requestJsonOnce(
        url: String
    ): JSONObject {

        val connection =
            URL(
                url
            ).openConnection()
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
            API_KEY
        )

        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android) FantasyCompanion/0.7"
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

            Log.d(
                TAG,
                "HTTP $code $url"
            )

            Log.d(
                TAG,
                "BODY=${body.take(500)}"
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
                    "Respuesta vacía"
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

        return nickname
            .ifBlank {
                "Jugador"
            }
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

    private fun jsonKeys(
        obj: JSONObject
    ): String {

        val keys =
            mutableListOf<String>()

        obj.keys()
            .forEachRemaining {

                keys.add(
                    it
                )
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

        return normalize(
            value
        ).replace(
            ' ',
            '_'
        )
    }
}
