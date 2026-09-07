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
        private const val MAX_OFFSET = 700

        private const val CONNECT_TIMEOUT = 5000
        private const val READ_TIMEOUT = 8000

        private val SUBSCRIPTIONS = listOf(
            "laliga-easports-2026",
            "laliga-hypermotion-2026"
        )

        /*
         * Fallback de la clave pública que ya estábamos utilizando.
         * Si cambia, discoverApiKey() intentará obtenerla primero.
         */
        private const val FALLBACK_API_KEY =
            "c13c3a8e2f6b46da9c5c425cf61fab3e"
    }

    private val main = Handler(Looper.getMainLooper())

    /*
     * Resultado final ya convertido a PlayerStats.
     */
    private val statsCache =
        ConcurrentHashMap<String, PlayerStats>()

    /*
     * Índice local:
     *
     * nombre normalizado -> JSONObject completo del jugador
     *
     * Cada página que descargamos se queda aquí.
     * Así NO volvemos a recorrer internet para jugadores que
     * ya hayan aparecido en páginas descargadas anteriormente.
     */
    private val playerIndex =
        ConcurrentHashMap<String, JSONObject>()

    /*
     * Evita descargar la misma página más de una vez.
     *
     * Ejemplo:
     * laliga-easports-2026:0
     * laliga-easports-2026:100
     */
    private val loadedPages =
        ConcurrentHashMap.newKeySet<String>()

    /*
     * Competiciones que sabemos que ya hemos recorrido completas.
     */
    private val exhaustedSubscriptions =
        ConcurrentHashMap.newKeySet<String>()

    /*
     * Evita que dos jugadores distintos hagan simultáneamente
     * la misma carga de páginas.
     */
    private val loadingLock = Any()

    @Volatile
    private var subscriptionKey: String? = null

    // ============================================================
    // API PÚBLICA DEL REPOSITORIO
    // ============================================================

    fun get(
        playerName: String,
        callback: (PlayerStats) -> Unit
    ) {
        val normalized =
            normalize(playerName)

        /*
         * 1. Resultado final ya conocido.
         */
        statsCache[normalized]?.let {
            callback(it)
            return
        }

        /*
         * Mostramos cargando inmediatamente.
         */
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

                fetchPlayer(
                    requestedName = playerName
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error inesperado buscando $playerName",
                    e
                )

                PlayerStats(
                    playerName = playerName,
                    error = e.message
                        ?: "Error consultando LALIGA"
                )
            }

            /*
             * Guardamos cualquier resultado válido.
             */
            if (
                result.error == null &&
                !result.loading
            ) {
                statsCache[normalized] = result
            }

            main.post {
                callback(result)
            }
        }
    }

    // ============================================================
    // BÚSQUEDA
    // ============================================================

    private fun fetchPlayer(
        requestedName: String
    ): PlayerStats {

        val target =
            normalize(requestedName)

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "BUSCANDO: '$requestedName' -> '$target'"
        )

        /*
         * PRIMERO buscamos únicamente en memoria.
         *
         * Esto será instantáneo si el jugador apareció en
         * alguna página descargada anteriormente.
         */
        findIndexedPlayer(target)?.let { obj ->

            Log.d(
                TAG,
                "Jugador encontrado directamente en caché"
            )

            return statsFromObject(
                requestedName,
                obj
            )
        }

        /*
         * Solo un hilo carga nuevas páginas a la vez.
         */
        synchronized(loadingLock) {

            /*
             * Otro hilo podría haberlo cargado mientras
             * esperábamos el lock.
             */
            findIndexedPlayer(target)?.let { obj ->

                return statsFromObject(
                    requestedName,
                    obj
                )
            }

            val apiKey =
                getApiKey()

            var networkFailures = 0
            var successfulRequests = 0

            /*
             * Primero Primera División.
             * Solo pasamos a Segunda si no aparece.
             */
            for (subscription in SUBSCRIPTIONS) {

                /*
                 * Si esta competición ya fue cargada completamente,
                 * no hacemos ninguna llamada.
                 */
                if (
                    exhaustedSubscriptions.contains(
                        subscription
                    )
                ) {

                    findIndexedPlayer(target)?.let {
                        return statsFromObject(
                            requestedName,
                            it
                        )
                    }

                    continue
                }

                Log.d(
                    TAG,
                    "Buscando en $subscription"
                )

                var subscriptionReachedEnd = false

                for (
                    offset in
                    0..MAX_OFFSET step PAGE_SIZE
                ) {

                    val pageId =
                        "$subscription:$offset"

                    /*
                     * Página ya descargada anteriormente.
                     */
                    if (
                        loadedPages.contains(pageId)
                    ) {

                        findIndexedPlayer(target)?.let {

                            Log.d(
                                TAG,
                                "Encontrado usando página ya cacheada: $pageId"
                            )

                            return statsFromObject(
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
                        "Descargando página $pageId"
                    )

                    val body = try {

                        requestWithRetry(
                            url = url,
                            apiKey = apiKey
                        )

                    } catch (e: Exception) {

                        networkFailures++

                        Log.w(
                            TAG,
                            "Fallo página $pageId: ${e.message}"
                        )

                        /*
                         * No seguimos haciendo 7 peticiones más
                         * si LALIGA está fallando ahora mismo.
                         */
                        break
                    }

                    successfulRequests++

                    val root =
                        parseAny(body)

                    /*
                     * Extraemos SOLO objetos que parecen
                     * registros completos de jugador.
                     */
                    val players =
                        extractPlayerObjects(root)

                    Log.d(
                        TAG,
                        "Página $pageId: ${players.size} jugadores detectados"
                    )

                    /*
                     * Índice permanente de la sesión.
                     */
                    players.forEach {
                        indexPlayer(it)
                    }

                    loadedPages.add(pageId)

                    /*
                     * Tras CADA página comprobamos el jugador.
                     *
                     * No esperamos a descargar 800 registros
                     * si estaba en la primera página.
                     */
                    findIndexedPlayer(target)?.let {

                        Log.d(
                            TAG,
                            "MATCH encontrado después de $pageId"
                        )

                        Log.d(
                            TAG,
                            "JSON MATCH: ${it.toString().take(2500)}"
                        )

                        return statsFromObject(
                            requestedName,
                            it
                        )
                    }

                    /*
                     * Si la API devuelve claramente menos que
                     * PAGE_SIZE, probablemente hemos llegado al final.
                     */
                    if (
                        players.isNotEmpty() &&
                        players.size < PAGE_SIZE / 2
                    ) {

                        Log.d(
                            TAG,
                            "Fin aparente de $subscription en offset $offset"
                        )

                        subscriptionReachedEnd = true
                        break
                    }
                }

                /*
                 * Marcamos como recorrida si llegamos al final
                 * natural o ya hemos cargado el último offset.
                 */
                if (
                    subscriptionReachedEnd ||
                    loadedPages.contains(
                        "$subscription:$MAX_OFFSET"
                    )
                ) {
                    exhaustedSubscriptions.add(
                        subscription
                    )
                }

                /*
                 * Comprobación adicional después de cada liga.
                 */
                findIndexedPlayer(target)?.let {

                    return statsFromObject(
                        requestedName,
                        it
                    )
                }
            }

            /*
             * Diferenciamos claramente:
             *
             * - no respondió internet/API
             * - sí respondió pero no encontramos al jugador
             */
            if (
                networkFailures > 0 &&
                successfulRequests == 0
            ) {

                return PlayerStats(
                    playerName = requestedName,
                    error = "LALIGA no responde. Inténtalo de nuevo."
                )
            }

            Log.w(
                TAG,
                "Jugador no encontrado: $requestedName"
            )

            return PlayerStats(
                playerName = requestedName,
                error = "Jugador no encontrado"
            )
        }
    }

    // ============================================================
    // ÍNDICE DE JUGADORES
    // ============================================================

    private fun indexPlayer(
        obj: JSONObject
    ) {

        /*
         * Guardamos todos los nombres/alias posibles.
         *
         * Ejemplo:
         *
         * Pedri
         * Pedro González López
         *
         * pueden apuntar al mismo JSONObject.
         */
        collectPlayerAliases(obj)
            .forEach { alias ->

                val key =
                    normalize(alias)

                if (key.length >= 3) {

                    playerIndex.putIfAbsent(
                        key,
                        obj
                    )
                }
            }
    }

    private fun findIndexedPlayer(
        target: String
    ): JSONObject? {

        /*
         * Coincidencia exacta: instantánea.
         */
        playerIndex[target]?.let {
            return it
        }

        /*
         * Coincidencia aproximada.
         *
         * Recorremos SOLO el índice en memoria,
         * no internet.
         */
        var bestObject: JSONObject? = null
        var bestScore = 0

        for (
            (candidateName, obj)
            in playerIndex
        ) {

            val score =
                namesMatchScore(
                    target,
                    candidateName
                )

            if (score > bestScore) {
                bestScore = score
                bestObject = obj
            }
        }

        Log.d(
            TAG,
            "Mejor coincidencia en índice: score=$bestScore"
        )

        /*
         * 75 mantiene soporte para jugadores conocidos
         * por un solo nombre: Pedri, Gavi, Raphinha...
         */
        return if (
            bestScore >= 75
        ) {
            bestObject
        } else {
            null
        }
    }

    private fun collectPlayerAliases(
        obj: JSONObject
    ): Set<String> {

        val aliases =
            linkedSetOf<String>()

        val keys = listOf(
            "nickname",
            "display_name",
            "player_name",
            "full_name",
            "name"
        )

        fun collect(
            source: JSONObject?
        ) {

            if (source == null) {
                return
            }

            for (key in keys) {

                val value =
                    source
                        .optString(key, "")
                        .trim()

                if (
                    value.length in 3..70 &&
                    value.any(Char::isLetter)
                ) {
                    aliases.add(value)
                }
            }
        }

        /*
         * Las estructuras específicas de jugador tienen prioridad.
         */
        collect(
            obj.optJSONObject("person")
        )

        collect(
            obj.optJSONObject("player")
        )

        /*
         * Después los campos del registro principal.
         */
        collect(obj)

        return aliases
    }

    // ============================================================
    // EXTRAER REGISTROS REALES DE JUGADOR
    // ============================================================

    private fun extractPlayerObjects(
        root: Any?
    ): List<JSONObject> {

        val result =
            mutableListOf<JSONObject>()

        val seen =
            HashSet<String>()

        fun walk(
            value: Any?,
            depth: Int
        ) {

            /*
             * Evitamos recorrer árboles JSON absurdamente profundos.
             */
            if (depth > 8) {
                return
            }

            when (value) {

                is JSONObject -> {

                    /*
                     * Solo aceptamos objetos con:
                     *
                     * - nombre de jugador
                     * - stats/statistics
                     *
                     * Esto mantiene la corrección que eliminó
                     * los falsos equipos.
                     */
                    if (
                        hasStatsContainer(value) &&
                        findPlayerName(value) != null
                    ) {

                        /*
                         * Intentamos deduplicar por ID,
                         * y como fallback por nombre.
                         */
                        val identity =
                            extractPlayerId(value)
                                ?: normalize(
                                    findPlayerName(value)
                                        ?: value.toString()
                                )

                        if (
                            seen.add(identity)
                        ) {
                            result.add(value)
                        }

                        /*
                         * Si ya es un registro de jugador,
                         * no hace falta entrar en stats[] y
                         * analizar cientos de objetos estadísticos.
                         *
                         * ESTA optimización es importante.
                         */
                        return
                    }

                    value
                        .keys()
                        .forEachRemaining { key ->

                            walk(
                                value.opt(key),
                                depth + 1
                            )
                        }
                }

                is JSONArray -> {

                    for (
                        i in 0 until value.length()
                    ) {

                        walk(
                            value.opt(i),
                            depth + 1
                        )
                    }
                }
            }
        }

        walk(
            root,
            0
        )

        return result
    }

    private fun extractPlayerId(
        obj: JSONObject
    ): String? {

        val keys = listOf(
            "opta_id",
            "optaId",
            "player_id",
            "playerId",
            "id"
        )

        /*
         * Primero estructuras específicas del jugador.
         */
        val containers = listOfNotNull(
            obj.optJSONObject("person"),
            obj.optJSONObject("player"),
            obj
        )

        for (container in containers) {

            for (key in keys) {

                val raw =
                    realValue(
                        container,
                        key
                    ) ?: continue

                val text =
                    raw.toString()
                        .trim()

                if (text.isNotBlank()) {
                    return "$key:$text"
                }
            }
        }

        return null
    }

    private fun hasStatsContainer(
        obj: JSONObject
    ): Boolean {

        val stats =
            realValue(
                obj,
                "stats"
            )

        val statistics =
            realValue(
                obj,
                "statistics"
            )

        return stats is JSONArray ||
            stats is JSONObject ||
            statistics is JSONArray ||
            statistics is JSONObject
    }

    // ============================================================
    // NOMBRE DEL JUGADOR
    // ============================================================

    private fun findPlayerName(
        obj: JSONObject
    ): String? {

        val keys = listOf(
            "nickname",
            "display_name",
            "player_name",
            "full_name",
            "name"
        )

        /*
         * Primero PERSON.
         */
        obj
            .optJSONObject("person")
            ?.let { person ->

                findNameInside(
                    person,
                    keys
                )?.let {
                    return it
                }
            }

        /*
         * Después PLAYER.
         */
        obj
            .optJSONObject("player")
            ?.let { player ->

                findNameInside(
                    player,
                    keys
                )?.let {
                    return it
                }
            }

        /*
         * Finalmente raíz.
         */
        return findNameInside(
            obj,
            keys
        )
    }

    private fun findNameInside(
        obj: JSONObject,
        keys: List<String>
    ): String? {

        for (key in keys) {

            val value =
                obj
                    .optString(key, "")
                    .trim()

            if (
                value.length in 3..70 &&
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

        if (
            requested == candidate
        ) {
            return 100
        }

        /*
         * Robert Lewandowski ↔ Lewandowski
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

        val requestedWords =
            requested
                .split(' ')
                .filter {
                    it.length > 2
                }
                .toSet()

        val candidateWords =
            candidate
                .split(' ')
                .filter {
                    it.length > 2
                }
                .toSet()

        if (
            requestedWords.isEmpty() ||
            candidateWords.isEmpty()
        ) {
            return 0
        }

        val common =
            requestedWords
                .intersect(
                    candidateWords
                )

        /*
         * Dos nombres de varias palabras:
         * exigimos al menos dos coincidencias.
         */
        if (
            requestedWords.size >= 2 &&
            candidateWords.size >= 2
        ) {

            return if (
                common.size >= 2
            ) {

                80 +
                    minOf(
                        common.size,
                        3
                    )

            } else {

                0
            }
        }

        /*
         * Pedri / Gavi / Raphinha...
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
    // ESTADÍSTICAS
    // ============================================================

    private fun statsFromObject(
        requestedName: String,
        obj: JSONObject
    ): PlayerStats {

        val stats =
            mutableMapOf<String, Int>()

        fun consumeStats(
            container: Any?
        ) {

            when (container) {

                is JSONArray -> {

                    for (
                        i in
                        0 until container.length()
                    ) {

                        val statObject =
                            container
                                .optJSONObject(i)
                                ?: continue

                        val pair =
                            extractStatPair(
                                statObject
                            )
                                ?: continue

                        stats[
                            normalizeStatKey(
                                pair.first
                            )
                        ] = pair.second

                        Log.d(
                            TAG,
                            "STAT ${pair.first}=${pair.second}"
                        )
                    }
                }

                is JSONObject -> {

                    container
                        .keys()
                        .forEachRemaining { key ->

                            val value =
                                realValue(
                                    container,
                                    key
                                )

                            val number =
                                toIntOrNull(
                                    value
                                )

                            if (
                                number != null
                            ) {

                                stats[
                                    normalizeStatKey(
                                        key
                                    )
                                ] = number
                            }
                        }
                }
            }
        }

        consumeStats(
            realValue(
                obj,
                "stats"
            )
        )

        consumeStats(
            realValue(
                obj,
                "statistics"
            )
        )

        /*
         * Algunos esquemas podrían exponer campos directos.
         */
        obj
            .keys()
            .forEachRemaining { key ->

                val raw =
                    realValue(
                        obj,
                        key
                    )

                val number =
                    toIntOrNull(
                        raw
                    )

                if (
                    number != null
                ) {

                    stats.putIfAbsent(
                        normalizeStatKey(key),
                        number
                    )
                }
            }

        Log.d(
            TAG,
            "STATS $requestedName -> $stats"
        )

        fun get(
            vararg keys: String
        ): Int? {

            for (key in keys) {

                stats[
                    normalizeStatKey(
                        key
                    )
                ]?.let {
                    return it
                }
            }

            return null
        }

        return PlayerStats(

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
    }

    /**
     * Soporta varias formas:
     *
     * {
     *   "name": "goals",
     *   "value": 3
     * }
     *
     * o:
     *
     * {
     *   "name": "goals",
     *   "stat": 3
     * }
     *
     * o:
     *
     * {
     *   "stat": "goals",
     *   "value": 3
     * }
     */
    private fun extractStatPair(
        obj: JSONObject
    ): Pair<String, Int>? {

        val keyFields =
            listOf(
                "name",
                "key",
                "stat_name",
                "statName",
                "type",
                "code"
            )

        var statName: String? = null

        for (key in keyFields) {

            val raw =
                realValue(
                    obj,
                    key
                )

            if (
                raw is String &&
                raw.any(Char::isLetter)
            ) {

                statName =
                    raw.trim()

                break
            }
        }

        val rawStat =
            realValue(
                obj,
                "stat"
            )

        /*
         * stat puede ser NOMBRE...
         */
        if (
            statName == null &&
            rawStat is String &&
            rawStat.any(Char::isLetter)
        ) {
            statName =
                rawStat.trim()
        }

        if (
            statName == null
        ) {
            return null
        }

        /*
         * ...o puede ser VALOR.
         */
        val possibleValues =
            listOf(
                realValue(
                    obj,
                    "value"
                ),
                realValue(
                    obj,
                    "total"
                ),
                realValue(
                    obj,
                    "stat_value"
                ),
                realValue(
                    obj,
                    "amount"
                ),
                realValue(
                    obj,
                    "count"
                ),
                rawStat
            )

        for (raw in possibleValues) {

            val number =
                toIntOrNull(
                    raw
                )

            if (
                number != null
            ) {

                return Pair(
                    statName,
                    number
                )
            }
        }

        return null
    }

    // ============================================================
    // API KEY
    // ============================================================

    private fun getApiKey(): String {

        subscriptionKey?.let {
            return it
        }

        val discovered = try {

            discoverApiKey()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "No se pudo descubrir API key: ${e.message}"
            )

            null
        }

        val key =
            discovered
                ?: FALLBACK_API_KEY

        subscriptionKey = key

        return key
    }

    private fun discoverApiKey(): String? {

        /*
         * Solo probamos una página para no añadir
         * varios timeouts antes incluso de consultar jugadores.
         */
        val html =
            requestOnce(
                url =
                    "https://www.laliga.com/",
                apiKey = null,
                connectTimeout = 3500,
                readTimeout = 5000
            )

        val regexes =
            listOf(

                Regex(
                    "backendSubscription" +
                        "[\\\"']?\\s*[:=]\\s*" +
                        "[\\\"']" +
                        "([a-fA-F0-9]{24,64})"
                ),

                Regex(
                    "Ocp-Apim-Subscription-Key" +
                        "[\\\"']?\\s*[:=]\\s*" +
                        "[\\\"']" +
                        "([a-fA-F0-9]{24,64})"
                )
            )

        for (regex in regexes) {

            regex
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.let {

                    Log.d(
                        TAG,
                        "API key descubierta"
                    )

                    return it
                }
        }

        return null
    }

    // ============================================================
    // HTTP
    // ============================================================

    private fun requestWithRetry(
        url: String,
        apiKey: String?
    ): String {

        var lastError:
            Exception? = null

        /*
         * Máximo DOS intentos.
         *
         * Nada de quedarse indefinidamente cargando.
         */
        repeat(2) { attempt ->

            try {

                return requestOnce(
                    url = url,
                    apiKey = apiKey,
                    connectTimeout =
                        CONNECT_TIMEOUT,
                    readTimeout =
                        READ_TIMEOUT
                )

            } catch (e: Exception) {

                lastError = e

                Log.w(
                    TAG,
                    "Petición fallida intento ${attempt + 1}/2: ${e.message}"
                )

                if (attempt == 0) {

                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        throw lastError
            ?: IllegalStateException(
                "Error de conexión"
            )
    }

    private fun requestOnce(
        url: String,
        apiKey: String?,
        connectTimeout: Int,
        readTimeout: Int
    ): String {

        val connection =
            URL(url)
                .openConnection()
                as HttpURLConnection

        connection.connectTimeout =
            connectTimeout

        connection.readTimeout =
            readTimeout

        connection.requestMethod =
            "GET"

        connection.setRequestProperty(
            "Accept",
            "application/json,text/html,*/*"
        )

        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android) FantasyCompanion/0.4"
        )

        if (
            apiKey != null
        ) {

            connection.setRequestProperty(
                "Ocp-Apim-Subscription-Key",
                apiKey
            )
        }

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

            val text =
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

        val trimmed =
            text.trimStart()

        if (
            trimmed.startsWith("[")
        ) {
            return JSONArray(text)
        }

        return JSONObject(text)
    }

    private fun realValue(
        obj: JSONObject,
        key: String
    ): Any? {

        if (
            !obj.has(key)
        ) {
            return null
        }

        val value =
            obj.opt(key)

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
