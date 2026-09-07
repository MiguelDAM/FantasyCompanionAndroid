package com.example.fantasycompanion

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class OverlayController(
    private val service: AccessibilityService
) {

    private val windowManager =
        service.getSystemService(
            WindowManager::class.java
        )

    private var card:
        LinearLayout? = null

    private var leagueView:
        TextView? = null

    private var nameView:
        TextView? = null

    private var statsView:
        TextView? = null

    private var statusView:
        TextView? = null

    fun show(
        stats: PlayerStats
    ) {

        if (
            card == null
        ) {

            createCard()
        }

        nameView?.text =
            stats.playerName

        leagueView?.text =
            when (
                stats.competition
            ) {

                "LALIGA HYPERMOTION" ->
                    "HYPERMOTION"

                "LALIGA EA SPORTS" ->
                    "EA SPORTS"

                else ->
                    "LALIGA"
            }

        fun value(
            number: Int?
        ): String {

            return number
                ?.toString()
                ?: "—"
        }

        /*
         * Dos líneas para reducir mucho el ancho.
         */
        statsView?.text =
            buildString {

                append("⚽ ")
                append(
                    value(
                        stats.goals
                    )
                )

                append("   🅰 ")
                append(
                    value(
                        stats.assists
                    )
                )

                append("   🟨 ")
                append(
                    value(
                        stats.yellowCards
                    )
                )

                append("\n")

                append("🟥 ")
                append(
                    value(
                        stats.redCards
                    )
                )

                append("   🧤 ")
                append(
                    value(
                        stats.cleanSheets
                    )
                )
            }

        statusView?.text =
            when {

                stats.loading ->
                    "Buscando estadísticas…"

                stats.error != null ->
                    "⚠ Datos no disponibles"

                !stats.teamName
                    .isNullOrBlank() ->
                    stats.teamName

                else ->
                    "Datos LALIGA"
            }
    }

    fun hide() {

        card?.let {

            try {

                windowManager.removeView(
                    it
                )

            } catch (
                _: Exception
            ) {
            }
        }

        card =
            null

        leagueView =
            null

        nameView =
            null

        statsView =
            null

        statusView =
            null
    }

    private fun createCard() {

        val density =
            service
                .resources
                .displayMetrics
                .density

        fun dp(
            value: Int
        ): Int {

            return (
                value *
                    density
                ).toInt()
        }

        val container =
            LinearLayout(
                service
            ).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(10),
                    dp(7),
                    dp(10),
                    dp(7)
                )

                setBackgroundResource(
                    R.drawable.overlay_background
                )

                elevation =
                    dp(8)
                        .toFloat()

                minimumWidth =
                    dp(145)
            }

        /*
         * Fila superior pequeña:
         * FC · EA SPORTS
         */
        val league =
            TextView(
                service
            ).apply {

                text =
                    "LALIGA"

                textSize =
                    8.5f

                setTextColor(
                    Color.parseColor(
                        "#B8FF4A"
                    )
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                letterSpacing =
                    0.07f
            }

        val name =
            TextView(
                service
            ).apply {

                text =
                    "Jugador"

                textSize =
                    13.5f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                maxWidth =
                    dp(175)

                maxLines =
                    1

                ellipsize =
                    TextUtils.TruncateAt.END

                setPadding(
                    0,
                    dp(1),
                    0,
                    0
                )
            }

        val stats =
            TextView(
                service
            ).apply {

                text =
                    "⚽ —   🅰 —   🟨 —\n🟥 —   🧤 —"

                textSize =
                    11.5f

                setTextColor(
                    Color.WHITE
                )

                setPadding(
                    0,
                    dp(4),
                    0,
                    0
                )

                maxLines =
                    2
            }

        val status =
            TextView(
                service
            ).apply {

                textSize =
                    8.5f

                setTextColor(
                    Color.parseColor(
                        "#AFFFFFFF"
                    )
                )

                maxWidth =
                    dp(175)

                maxLines =
                    1

                ellipsize =
                    TextUtils.TruncateAt.END

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )
            }

        container.addView(
            league
        )

        container.addView(
            name
        )

        container.addView(
            stats
        )

        container.addView(
            status
        )

        val landscape =
            service
                .resources
                .displayMetrics
                .widthPixels >
                service
                    .resources
                    .displayMetrics
                    .heightPixels

        val params =
            WindowManager.LayoutParams(

                WindowManager.LayoutParams.WRAP_CONTENT,

                WindowManager.LayoutParams.WRAP_CONTENT,

                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,

                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,

                PixelFormat.TRANSLUCENT
            ).apply {

                if (
                    landscape
                ) {

                    /*
                     * En tu captura:
                     * espacio libre inferior derecho.
                     */
                    gravity =
                        Gravity.BOTTOM or
                            Gravity.END

                    x =
                        dp(12)

                    y =
                        dp(18)

                } else {

                    /*
                     * En vertical evitamos la zona inferior
                     * de botones/navegación.
                     */
                    gravity =
                        Gravity.TOP or
                            Gravity.END

                    x =
                        dp(8)

                    y =
                        dp(150)
                }
            }

        windowManager.addView(
            container,
            params
        )

        card =
            container

        leagueView =
            league

        nameView =
            name

        statsView =
            stats

        statusView =
            status
    }
}
