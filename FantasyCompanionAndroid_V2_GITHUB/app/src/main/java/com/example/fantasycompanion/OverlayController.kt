package com.example.fantasycompanion

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayController(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var card: LinearLayout? = null
    private var titleView: TextView? = null
    private var nameView: TextView? = null
    private var statsView: TextView? = null
    private var statusView: TextView? = null

    fun show(stats: PlayerStats) {
        if (card == null) createCard()

        nameView?.text = stats.playerName

        fun value(number: Int?) = number?.toString() ?: "—"
        statsView?.text = buildString {
            append("⚽ ").append(value(stats.goals))
            append("   🅰️ ").append(value(stats.assists))
            append("   🟨 ").append(value(stats.yellowCards))
            append("   🟥 ").append(value(stats.redCards))
            append("   🧤 ").append(value(stats.cleanSheets))
        }

        statusView?.text = when {
            stats.loading -> "Buscando estadísticas…"
            stats.error != null -> "Datos no disponibles · ${stats.error}"
            stats.source != null -> "Datos: ${stats.source}"
            else -> ""
        }
    }

    fun hide() {
        card?.let {
            try { windowManager.removeView(it) } catch (_: Exception) { }
        }
        card = null
        titleView = null
        nameView = null
        statsView = null
        statusView = null
    }

    private fun createCard() {
        val density = service.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.overlay_background)
            elevation = dp(10).toFloat()
        }

        val title = TextView(service).apply {
            text = "Fantasy Companion · V2"
            textSize = 11f
            alpha = 0.65f
        }
        val name = TextView(service).apply {
            text = "Jugador"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val stats = TextView(service).apply {
            text = "⚽ —   🅰️ —   🟨 —   🟥 —   🧤 —"
            textSize = 15f
            setPadding(0, dp(5), 0, 0)
        }
        val status = TextView(service).apply {
            textSize = 10f
            alpha = 0.6f
            setPadding(0, dp(3), 0, 0)
        }

        container.addView(title)
        container.addView(name)
        container.addView(stats)
        container.addView(status)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }

        windowManager.addView(container, params)
        card = container
        titleView = title
        nameView = name
        statsView = stats
        statusView = status
    }
}
