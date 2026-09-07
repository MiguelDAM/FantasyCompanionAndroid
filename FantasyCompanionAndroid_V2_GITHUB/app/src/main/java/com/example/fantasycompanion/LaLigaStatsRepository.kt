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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class LaLigaStatsRepository {

    companion object {

        private const val TAG =
            "FantasyAPI"

        private const val BASE_URL =
            "https://apim.laliga.com/public-service"

        private const val API_KEY =
            "c13c3a8e2f6b46da9c5c425cf61fab3e"

        private const val PAGE_SIZE =
            100

        private const val CONNECT_TIMEOUT =
            7000

        private const val READ_TIMEOUT =
            12000

        private data class CompetitionConfig(

            val slug: String,

            val label: String
        )

        /*
         * Sistema escalable.
         *
         * Para añadir otra competición en el futuro,
         * basta con añadir otra configuración aquí.
         */
        private val COMPETITIONS =
            listOf(

                CompetitionConfig(
                    slug =
                        "laliga-easports-2026",

                    label =
                        "LALIGA EA SPORTS"
                ),

                CompetitionConfig(
                    slug =
                        "laliga-hypermotion-2026",

                    label =
                        "LALIGA HYPERMOTION"
                )
            )
    }

    private data class IndexedPlayer(

        val player: JSONObject,

        val competition:
            CompetitionConfig
    )

    private data class PlayerMatch(

        val uiCandidate: String,

        val apiAlias: String,

        val entry: IndexedPlayer,

        val score: Int
    )

    private data class Diagnostic(

        val alias: String,

        val score: Int
    )

    private val main =
        Handler(
            Looper.getMainLooper()
        )

    /*
     * Un alias puede existir en más de una competición.
     *
     * Por eso ahora no usamos:
     *
     * alias -> jugador
     *
     * sino:
     *
     * alias -> lista de posibles jugadores.
     */
    private val playerIndex =
        ConcurrentHashMap<
            String,
            CopyOnWriteArrayList<IndexedPlayer>
        >()

    private val statsCache =
        ConcurrentHashMap<
            String,
            PlayerStats
        >()

    private val loadedSubscriptions =
        ConcurrentHashMap
            .newKeySet<String>()

    private val loadLock =
        Any()

    // ============================================================
    // API
    // ============================================================

    fun get(
        playerName: String,
        callback: (PlayerStats) -> Unit
    ) {

        get(
            candidateNames =
                listOf(playerName),

            callback =
                callback
        )
    }

    fun get(
        candidateNames: List<String>,
        callback: (PlayerStats) -> Unit
    ) {

        val candidates =
            candidateNames
                .map {
                    it.trim()
                }
                .filter {
                    it.length >= 3
                }
                .distinctBy {
                    normalize(it)
                }
                .take(24)

        if (
            candidates.isEmpty()
        ) {

            callback(

                PlayerStats(
                    playerName =
                        "Jugador",

                    error =
                        "No se detectó ningún candidato"
                )
            )

            return
        }

        callback(

            PlayerStats(
                playerName =
                    "Identificando jugador…",

                loading =
                    true
            )
        )

        thread(
            name =
                "laliga-stats",

            isDaemon =
                true
        ) {

            val result =
                try {

                    findStatsForCandidates(
                        candidates
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Error buscando $candidates",
                        e
                    )

                    PlayerStats(
                        playerName =
                            candidates.first(),

                        error =
                            e.message
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
    // MOTOR MULTI-COMPETICIÓN
    // ============================================================

    private fun findStatsForCandidates(
        candidates: List<String>
    ): PlayerStats {

        Log.d(
            TAG,
            "======================================"
        )

        Log.d(
            TAG,
            "CANDIDATOS: ${candidates.joinToString(" | ")}"
        )

        /*
         * Si ya tenemos un match muy fuerte en caché,
         * no hacemos red.
         */
        findBestAcrossCandidates(
            candidates
        )
            ?.takeIf {
                it.score >= 90
            }
            ?.let {

                return resultForMatch(
                    it
                )
            }

        synchronized(
            loadLock
        ) {

            findBestAcrossCandidates(
                candidates
            )
                ?.takeIf {
                    it.score >= 90
                }
                ?.let {

                    return resultForMatch(
                        it
                    )
                }

            var successfulLoads =
                0

            var lastError:
                String? = null

            /*
             * Vamos cargando competiciones.
             *
             * Si aparece un match exacto (100),
             * podemos devolver inmediatamente.
             */
            for (
                competition
                in COMPETITIONS
            ) {

                if (
                    !loadedSubscriptions
                        .contains(
                            competition.slug
                        )
                ) {

                    try {

                        loadCompetition(
                            competition
                        )

                        loadedSubscriptions.add(
                            competition.slug
                        )

                        successfulLoads++

                    } catch (
                        e: Exception
                    ) {

                        lastError =
                            e.message

                        Log.e(
                            TAG,
                            "Error cargando ${competition.label}",
                            e
                        )

                        continue
                    }
                }

                val currentBest =
                    findBestAcrossCandidates(
                        candidates
                    )

                if (
                    currentBest != null &&
                    currentBest.score >= 100
                ) {

                    return resultForMatch(
                        currentBest
                    )
                }
            }

            /*
             * Con Primera + Segunda disponibles,
             * elegimos el mejor global.
             */
            findBestAcrossCandidates(
                candidates
            )
                ?.takeIf {
                    it.score >= 75
                }
                ?.let {

                    return resultForMatch(
                        it
                    )
                }

            val diagnostic =
                bestDiagnostic(
                    candidates
                )

            if (
                successfulLoads == 0 &&
                playerCount() == 0
            ) {

                return PlayerStats(

                    playerName =
                        candidates.first(),

                    error =
                        lastError
                            ?: "No se pudieron cargar LALIGA EA SPORTS ni HYPERMOTION"
                )
            }

            return PlayerStats(

                playerName =
                    candidates.first(),

                error =
                    "Jugador no encontrado · " +
                        "mejor API: " +
                        diagnostic.alias.ifBlank {
                            "—"
                        } +
                        " (${diagnostic.score})"
            )
        }
    }

    // ============================================================
    // CARGAR COMPETICIÓN
    // ============================================================

    private fun loadCompetition(
        competition:
            CompetitionConfig
    ) {

        Log.d(
            TAG,
            "CARGANDO ${competition.label}"
        )

        var offset =
            0

        while (
            offset < 1500
        ) {

            val url =
                "$BASE_URL/api/v1/" +
                    "subscriptions/${competition.slug}/" +
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
                    ?: throw IllegalStateException(
                        "${competition.label}: respuesta sin player_stats"
                    )

            val total =
                root.optInt(
                    "total",
                    -1
                )

            Log.d(
                TAG,
                "${competition.label}: " +
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
                players,
                competition
            )

            if (
                players.length() <
                PAGE_SIZE
            ) {
                break
            }

            offset +=
                PAGE_SIZE
        }

        Log.d(
            TAG,
            "${competition.label} cargada. " +
                "Jugadores indexados=${playerCount()}"
        )
    }

    // ============================================================
    // INDEX
    // ============================================================

    private fun indexPlayers(
        players: JSONArray,
        competition:
            CompetitionConfig
    ) {

        for (
            i in
            0 until players.length()
        ) {

            val player =
                players
                    .optJSONObject(i)
                    ?: continue

            val entry =
                IndexedPlayer(
                    player =
                        player,

                    competition =
                        competition
                )

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

            aliases.forEach {
                alias ->

                addIndexedAlias(
                    alias,
                    entry
                )
            }
        }
    }

    private fun addIndexedAlias(
        alias: String,
        entry: IndexedPlayer
    ) {

        val normalized =
            normalize(
                alias
            )

        if (
            normalized.length < 3
        ) {
            return
        }

        val bucket =
            playerIndex
                .computeIfAbsent(
                    normalized
                ) {

                    CopyOnWriteArrayList()
                }

        val identity =
            playerIdentity(
                entry
            )

        if (
            bucket.none {

                playerIdentity(it) ==
                    identity

            }
        ) {

            bucket.add(
                entry
            )
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
    // MATCH
    // ============================================================

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
             * Exactos.
             */
            playerIndex[
                requested
            ]?.forEach {
                entry ->

                val score =
                    100 +
                        contextBonus(
                            entry,
                            candidates
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
                                requested,

                            entry =
                                entry,

                            score =
                                score
                        )
                }
            }

            /*
             * Aproximados.
             */
            for (
                (apiAlias, entries)
                in playerIndex
            ) {

                val baseScore =
                    scoreName(
                        requested,
                        apiAlias
                    )

                if (
                    baseScore == 0
                ) {
                    continue
                }

                entries.forEach {
                    entry ->

                    val score =
                        baseScore +
                            contextBonus(
                                entry,
                                candidates
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

                                entry =
                                    entry,

                                score =
                                    score
                            )
                    }
                }
            }
        }

        best?.let {

            Log.d(
                TAG,
                "BEST: " +
                    "UI='${it.uiCandidate}' " +
                    "API='${it.apiAlias}' " +
                    "score=${it.score} " +
                    "competition=${it.entry.competition.label} " +
                    "team=${teamName(it.entry.player)}"
            )
        }

        return best
    }

    /*
     * Aprovechamos algo que Fantasy ya nos da:
     * normalmente el equipo también está visible.
     *
     * Eso ayuda a desempatar jugadores con nombres parecidos
     * entre Primera y Segunda.
     */
    private fun contextBonus(
        entry: IndexedPlayer,
        candidates: List<String>
    ): Int {

        val team =
            normalize(
                teamName(
                    entry.player
                )
            )

        if (
            team.isBlank()
        ) {
            return 0
        }

        for (
            candidate in candidates
        ) {

            val value =
                normalize(
                    candidate
                )

            if (
                value == team
            ) {
                return 12
            }

            if (
                value.length >= 4 &&
                team.length >= 4 &&
                (
                    value.contains(team) ||
                        team.contains(value)
                    )
            ) {
                return 8
            }
        }

        return 0
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
            requested ==
            candidate
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

    private fun bestDiagnostic(
        candidates: List<String>
    ): Diagnostic {

        var bestAlias =
            ""

        var bestScore =
            0

        for (
            candidate in candidates
        ) {

            val requested =
                normalize(
                    candidate
                )

            for (
                alias in playerIndex.keys
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
    // RESULTADO
    // ============================================================

    private fun resultForMatch(
        match: PlayerMatch
    ): PlayerStats {

        val entry =
            match.entry

        val player =
            entry.player

        val cacheKey =
            entry.competition.slug +
                ":" +
                normalize(
                    playerDisplayName(
                        player
                    )
                )

        statsCache[
            cacheKey
        ]?.let {

            return it
        }

        Log.d(
            TAG,
            "MATCH DEFINITIVO: " +
                "${playerDisplayName(player)} " +
                "| ${entry.competition.label} " +
                "| ${teamName(player)}"
        )

        val result =
            fetchIndividualStats(
                entry
            )
                ?: parsePlayerStats(
                    entry
                )

        if (
            result.error == null
        ) {

            statsCache[
                cacheKey
            ] =
                result
        }

        return result
    }

    // ============================================================
    // ENDPOINT INDIVIDUAL
    // ============================================================

    private fun fetchIndividualStats(
        entry: IndexedPlayer
    ): PlayerStats? {

        val indexedPlayer =
            entry.player

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

            /*
             * Protección extra:
             * el endpoint individual debe representar
             * el mismo jugador que el indexado.
             */
            if (
                !samePlayer(
                    indexedPlayer,
                    player
                )
            ) {

                Log.w(
                    TAG,
                    "Endpoint individual devolvió otro jugador. Usando listado."
                )

                return null
            }

            parsePlayerStats(

                IndexedPlayer(
                    player =
                        player,

                    competition =
                        entry.competition
                )
            )

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Individual falló: ${e.message}"
            )

            null
        }
    }

    // ============================================================
    // PARSEO
    // ============================================================

    private fun parsePlayerStats(
        entry: IndexedPlayer
    ): PlayerStats {

        val player =
            entry.player

        val map =
            mutableMapOf<
                String,
                Int
            >()

        val stats =
            player.optJSONArray(
                "stats"
            )

        if (
            stats == null
        ) {

            return PlayerStats(

                playerName =
                    playerDisplayName(
                        player
                    ),

                teamName =
                    teamName(
                        player
                    ),

                competition =
                    entry.competition.label,

                error =
                    "Jugador encontrado pero sin estadísticas"
            )
        }

        for (
            i in
            0 until stats.length()
        ) {

            val item =
                stats
                    .optJSONObject(i)
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

                map[
                    normalizeStatKey(
                        name
                    )
                ] =
                    value
            }
        }

        fun stat(
            vararg names: String
        ): Int? {

            names.forEach {
                name ->

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

        return PlayerStats(

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

            teamName =
                teamName(
                    player
                ),

            competition =
                entry
                    .competition
                    .label,

            source =
                "LALIGA"
        )
    }

    // ============================================================
    // HTTP
    // ============================================================

    private fun requestJson(
        url: String
    ): JSONObject {

        var lastError:
            Exception? = null

        repeat(2) {
            attempt ->

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
            "Mozilla/5.0 (Android) FantasyCompanion/0.8"
        )

        try {

            val code =
                connection.responseCode

            val stream =
                if (
                    code in
                    200..299
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

            if (
                code !in
                200..299
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
    // HELPERS
    // ============================================================

    private fun playerCount():
        Int {

        val identities =
            HashSet<String>()

        playerIndex
            .values
            .forEach {
                bucket ->

                bucket.forEach {
                    entry ->

                    identities.add(
                        playerIdentity(
                            entry
                        )
                    )
                }
            }

        return identities.size
    }

    private fun playerIdentity(
        entry: IndexedPlayer
    ): String {

        val player =
            entry.player

        val opta =
            player
                .optString(
                    "opta_id",
                    ""
                )
                .trim()

        if (
            opta.isNotBlank()
        ) {

            return entry.competition.slug +
                ":opta:" +
                opta
        }

        val slug =
            player
                .optString(
                    "slug",
                    ""
                )
                .trim()

        return entry.competition.slug +
            ":" +
            slug.ifBlank {

                normalize(
                    playerDisplayName(
                        player
                    )
                )
            }
    }

    private fun samePlayer(
        a: JSONObject,
        b: JSONObject
    ): Boolean {

        val optaA =
            a
                .optString(
                    "opta_id",
                    ""
                )
                .trim()

        val optaB =
            b
                .optString(
                    "opta_id",
                    ""
                )
                .trim()

        if (
            optaA.isNotBlank() &&
            optaB.isNotBlank()
        ) {

            return optaA ==
                optaB
        }

        val slugA =
            a
                .optString(
                    "slug",
                    ""
                )
                .trim()

        val slugB =
            b
                .optString(
                    "slug",
                    ""
                )
                .trim()

        return slugA.isNotBlank() &&
            slugA ==
            slugB
    }

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

        return player
            .optString(
                "nickname",
                "Jugador"
            )
            .trim()
            .ifBlank {
                "Jugador"
            }
    }

    private fun teamName(
        player: JSONObject
    ): String {

        val team =
            player
                .optJSONObject(
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
