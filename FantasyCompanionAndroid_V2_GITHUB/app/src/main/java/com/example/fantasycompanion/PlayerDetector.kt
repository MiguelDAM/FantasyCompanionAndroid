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
        val candidates: List<Candidate>
    )

    private val exactIgnored = setOf(
        "mis ligas", "anadir a favoritos", "añadir a favoritos", "favoritos",
        "mercado", "plantilla", "alineacion", "alineación", "clasificacion", "clasificación",
        "jornada", "liga", "ligas", "fichar", "vender", "pujar", "cancelar", "aceptar",
        "volver", "equipo", "puntos", "estadisticas", "estadísticas", "noticias", "posicion",
        "posición", "laliga fantasy", "fantasy", "inicio", "mas", "más", "menos", "cerrar",
        "compartir", "informacion", "información", "rendimiento", "valor de mercado", "precio",
        "ultima jornada", "última jornada", "proxima jornada", "próxima jornada", "ver mas", "ver más"
    )

    private val containsIgnored = listOf(
        "añadir a", "anadir a", "mis liga", "valor de mercado", "puntos fantasy", "jornada",
        "comprar", "vender", "clausula", "cláusula", "millones", "alineacion", "alineación"
    )

    fun detect(root: AccessibilityNodeInfo?): Detection {
        if (root == null) return Detection(null, 0, emptyList(), emptyList())

        val strings = LinkedHashSet<String>()
        val candidates = mutableListOf<Candidate>()
        walk(root, strings, candidates, 0)

        val hasProfileContext = strings.any {
            val n = normalize(it)
            n.contains("favorito") || n.contains("valor de mercado") ||
                n == "estadisticas" || n == "estadísticas" || n.contains("puntos")
        }

        val ranked = candidates
            .distinctBy { normalize(it.value) }
            .sortedByDescending { it.score }

        val best = ranked.firstOrNull()
        val required = if (hasProfileContext) 7 else 10
        val selected = best?.takeIf { it.score >= required }

        return Detection(
            playerName = selected?.value,
            confidence = selected?.score ?: 0,
            visibleStrings = strings.toList(),
            candidates = ranked.take(8)
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        strings: MutableSet<String>,
        candidates: MutableList<Candidate>,
        depth: Int
    ) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val viewId = node.viewIdResourceName
        val values = listOfNotNull(
            node.text?.toString()?.trim(),
            node.contentDescription?.toString()?.trim()
        ).filter { it.isNotBlank() }

        for (value in values) {
            strings.add(value)
            score(value, viewId, bounds.top, depth, node.className?.toString())?.let(candidates::add)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    walk(child, strings, candidates, depth + 1)
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
        val value = raw.trim().replace(Regex("\\s+"), " ")
        val normalized = normalize(value)
        if (value.length !in 4..42) return null
        if (normalized in exactIgnored) return null
        if (containsIgnored.any { normalized.contains(it) }) return null
        if (value.any(Char::isDigit)) return null
        if (value.contains('€') || value.contains('%') || value.contains(':')) return null
        if (value.count(Char::isLetter) < 4) return null

        val words = value.split(' ').filter(String::isNotBlank)
        if (words.size !in 1..5) return null

        var score = 0
        if (words.size in 2..4) score += 5 else score += 1
        if (value.length in 7..28) score += 2

        val plausibleWords = words.count { word ->
            val first = word.firstOrNull() ?: return@count false
            first.isUpperCase() || word.all(Char::isUpperCase)
        }
        if (plausibleWords == words.size) score += 3
        else if (plausibleWords >= words.size - 1) score += 1

        val id = viewId?.lowercase().orEmpty()
        if (listOf("player", "name", "nombre", "title").any(id::contains)) score += 6
        if (listOf("button", "tab", "menu", "nav").any(id::contains)) score -= 5

        if (className?.contains("Button", ignoreCase = true) == true) score -= 5
        if (top in 0..900) score += 2
        if (top in 0..500) score += 1
        if (depth in 2..10) score += 1

        return Candidate(value, score, viewId, top, depth)
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .trim()
    }
}
