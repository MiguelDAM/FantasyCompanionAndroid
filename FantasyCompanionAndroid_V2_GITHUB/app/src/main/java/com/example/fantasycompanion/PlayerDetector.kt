package com.example.fantasycompanion

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

object PlayerDetector {

    data class Candidate(
        val value: String,
        val score: Int,
        val viewId: String?,
        val top: Int,
        val depth: Int
    )

    data class Detection(
        val playerName: String?,
        val confidence: Int,
        val visibleStrings: List<String>,
        val candidates: List<Candidate>,
        val isPlayerProfile: Boolean
    )

    private val exactIgnored = setOf(
        "mis ligas",
        "anadir a favoritos",
        "favoritos",
        "mercado",
        "plantilla",
        "alineacion",
        "clasificacion",
        "jornada",
        "liga",
        "ligas",
        "fichar",
        "vender",
        "pujar",
        "cancelar",
        "aceptar",
        "volver",
        "equipo",
        "puntos",
        "estadisticas",
        "noticias",
        "posicion",
        "laliga fantasy",
        "fantasy",
        "inicio",
        "mas",
        "menos",
        "cerrar",
        "compartir",
        "informacion",
        "rendimiento",
        "valor de mercado",
        "precio",
        "ultima jornada",
        "proxima jornada",
        "ver mas",
        "acciones",
        "valor historico",
        "alineable"
    )

    private val containsIgnored = listOf(
        "anadir a",
        "mis liga",
        "valor de mercado",
        "puntos fantasy",
        "jornada",
        "comprar",
        "vender",
        "clausula",
        "millones",
        "alineacion",
        "valor historico"
    )

    /*
     * Textos característicos de una ficha individual.
     *
     * Incluye tanto la interfaz que estás viendo ahora
     * como variantes anteriores de LALIGA Fantasy.
     */
    private val profileMarkers = listOf(
        "alineable",
        "valor historico",
        "acciones",
        "anadir a favoritos",
        "valor de mercado",
        "estadisticas",
        "clausula",
        "media",
        "pfsy"
    )

    fun detect(
        root: AccessibilityNodeInfo?
    ): Detection {

        if (root == null) {

            return Detection(
                playerName = null,
                confidence = 0,
                visibleStrings = emptyList(),
                candidates = emptyList(),
                isPlayerProfile = false
            )
        }

        val strings =
            LinkedHashSet<String>()

        val candidates =
            mutableListOf<Candidate>()

        walk(
            node = root,
            strings = strings,
            candidates = candidates,
            depth = 0
        )

        val normalizedStrings =
            strings.map {
                normalize(it)
            }

        /*
         * Detección de ficha.
         */
        val markerCount =
            profileMarkers.count { marker ->

                normalizedStrings.any { visible ->
                    visible == marker ||
                        visible.contains(marker)
                }
            }

        /*
         * Hay algunas versiones de la ficha en las que solo
         * aparecen uno o dos marcadores fuertes.
         */
        val hasStrongProfileMarker =
            normalizedStrings.any {
                it.contains("valor historico") ||
                    it.contains("alineable") ||
                    it.contains("anadir a favoritos")
            }

        val ranked =
            candidates
                .distinctBy {
                    normalize(it.value)
                }
                .sortedByDescending {
                    it.score
                }

        /*
         * ANTES: take(8)
         *
         * Esto podía eliminar Mbappé, Camara, etc. aunque
         * estuviesen perfectamente visibles en pantalla.
         */
        val expandedCandidates =
            ranked.take(24)

        val best =
            expandedCandidates.firstOrNull()

        val isProfile =
            (
                markerCount >= 2 ||
                    (
                        hasStrongProfileMarker &&
                            expandedCandidates.isNotEmpty()
                        )
                )

        val selected =
            if (isProfile) {
                best
            } else {
                null
            }

        return Detection(
            playerName =
                selected?.value,

            confidence =
                selected?.score ?: 0,

            visibleStrings =
                strings.toList(),

            candidates =
                expandedCandidates,

            isPlayerProfile =
                isProfile
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        strings: MutableSet<String>,
        candidates: MutableList<Candidate>,
        depth: Int
    ) {

        val bounds =
            Rect().also(
                node::getBoundsInScreen
            )

        val viewId =
            node.viewIdResourceName

        val values =
            listOfNotNull(
                node.text
                    ?.toString()
                    ?.trim(),

                node.contentDescription
                    ?.toString()
                    ?.trim()
            )
                .filter {
                    it.isNotBlank()
                }

        for (value in values) {

            strings.add(
                value
            )

            score(
                raw = value,
                viewId = viewId,
                top = bounds.top,
                depth = depth,
                className =
                    node.className
                        ?.toString()
            )?.let {

                candidates.add(
                    it
                )
            }
        }

        for (
            i in 0 until node.childCount
        ) {

            node.getChild(i)?.let { child ->

                try {

                    walk(
                        node = child,
                        strings = strings,
                        candidates = candidates,
                        depth = depth + 1
                    )

                } finally {

                    child.recycle()
                }
            }
        }
    }

    private fun score(
        raw: String,
        viewId: String?,
        top: Int,
        depth: Int,
        className: String?
    ): Candidate? {

        val value =
            raw
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        val normalized =
            normalize(
                value
            )

        /*
         * Dejamos pasar nombres cortos como:
         *
         * Gavi
         * Suso
         * etc.
         */
        if (
            value.length !in 3..50
        ) {
            return null
        }

        if (
            normalized in exactIgnored
        ) {
            return null
        }

        if (
            containsIgnored.any {
                normalized.contains(it)
            }
        ) {
            return null
        }

        /*
         * Un nombre de jugador no debería contener cifras.
         */
        if (
            value.any(
                Char::isDigit
            )
        ) {
            return null
        }

        if (
            value.contains('€') ||
            value.contains('%') ||
            value.contains(':')
        ) {
            return null
        }

        if (
            value.count(
                Char::isLetter
            ) < 3
        ) {
            return null
        }

        val words =
            value
                .split(' ')
                .filter(
                    String::isNotBlank
                )

        if (
            words.size !in 1..6
        ) {
            return null
        }

        var score =
            0

        /*
         * Dos o tres palabras es un patrón muy típico
         * de nombres completos.
         */
        if (
            words.size in 2..4
        ) {

            score += 5

        } else {

            /*
             * Pero no penalizamos demasiado nombres únicos:
             * Mbappé, Camara, Pedri, Gavi...
             */
            score += 3
        }

        if (
            value.length in 4..30
        ) {
            score += 2
        }

        val plausibleWords =
            words.count { word ->

                val first =
                    word.firstOrNull()
                        ?: return@count false

                first.isUpperCase() ||
                    word.all(
                        Char::isUpperCase
                    )
            }

        if (
            plausibleWords ==
            words.size
        ) {

            score += 3

        } else if (
            plausibleWords >=
            words.size - 1
        ) {

            score += 1
        }

        val id =
            viewId
                ?.lowercase()
                .orEmpty()

        if (
            listOf(
                "player",
                "name",
                "nombre",
                "title"
            ).any(
                id::contains
            )
        ) {

            score += 6
        }

        if (
            listOf(
                "button",
                "tab",
                "menu",
                "nav"
            ).any(
                id::contains
            )
        ) {

            score -= 5
        }

        if (
            className?.contains(
                "Button",
                ignoreCase = true
            ) == true
        ) {

            score -= 5
        }

        /*
         * Nombre y equipo suelen encontrarse
         * en la mitad superior de la ficha.
         */
        if (
            top in 0..1200
        ) {
            score += 2
        }

        if (
            top in 0..650
        ) {
            score += 1
        }

        if (
            depth in 2..12
        ) {
            score += 1
        }

        return Candidate(
            value = value,
            score = score,
            viewId = viewId,
            top = top,
            depth = depth
        )
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
}
