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
        service.getSystemService(WindowManager::class.java)

    private var card: LinearLayout? = null

    private var titleView: TextView? = null
    private var competitionView: TextView? = null
    private var nameView: TextView? = null
    private var statsView: TextView? = null
    private var statusView: TextView? = null

    fun show(stats: PlayerStats) {

        if (card == null) {
            createCard()
        }

        nameView?.text =
            stats.playerName

        competitionView?.text =
            when (stats.competition) {

                "LALIGA EA SPORTS" ->
                    "LALIGA EA SPORTS"

                "LALIGA HYPERMOTION" ->
                    "LALIGA HYPERMOTION"

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

        statsView?.text =
            buildString {

                append("⚽ ")
                append(value(stats.goals))

                append("   🅰 ")
                append(value(stats.assists))

                append("   🟨 ")
                append(value(stats.yellowCards))

                append("   🟥 ")
                append(value(stats.redCards))

                append("   🧤 ")
                append(value(stats.cleanSheets))
            }

        statusView?.text =
            when {

                stats.loading ->
                    "Identificando jugador…"

                stats.error != null ->
                    "⚠ ${stats.error}"

                else -> {

                    listOfNotNull(
                        stats.teamName
                            ?.takeIf {
                                it.isNotBlank()
                            },

                        stats.source
                            ?.takeIf {
                                it.isNotBlank()
                            }
                    )
                        .joinToString("  ·  ")
                }
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

        card = null
        titleView = null
        competitionView = null
        nameView = null
        statsView = null
        statusView = null
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
                    dp(12),
                    dp(8),
                    dp(12),
                    dp(8)
                )

                setBackgroundResource(
                    R.drawable.overlay_background
                )

                elevation =
                    dp(12)
                        .toFloat()

                minimumWidth =
                    dp(190)
            }

        val title =
            TextView(
                service
            ).apply {

                text =
                    "FANTASY COMPANION"

                textSize =
                    9f

                setTextColor(
                    Color.WHITE
                )

                alpha =
                    0.60f

                letterSpacing =
                    0.08f

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }

        val competition =
            TextView(
                service
            ).apply {

                text =
                    "LALIGA"

                textSize =
                    9.5f

                setTextColor(
                    Color.parseColor(
                        "#B8FF4A"
                    )
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                setPadding(
                    0,
                    dp(1),
                    0,
                    dp(2)
                )
            }

        val name =
            TextView(
                service
            ).apply {

                text =
                    "Identificando jugador…"

                textSize =
                    15f

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    typeface,
                    Typeface.BOLD
                )

                maxWidth =
                    dp(235)

                maxLines =
                    1

                ellipsize =
                    TextUtils.TruncateAt.END
            }

        val stats =
            TextView(
                service
            ).apply {

                text =
                    "⚽ —   🅰 —   🟨 —   🟥 —   🧤 —"

                textSize =
                    12.5f

                setTextColor(
                    Color.WHITE
                )

                setPadding(
                    0,
                    dp(5),
                    0,
                    0
                )

                maxLines =
                    1
            }

        val status =
            TextView(
                service
            ).apply {

                textSize =
                    9.5f

                setTextColor(
                    Color.parseColor(
                        "#B8FFFFFF"
                    )
                )

                setPadding(
                    0,
                    dp(3),
                    0,
                    0
                )

                maxWidth =
                    dp(235)

                maxLines =
                    2

                ellipsize =
                    TextUtils.TruncateAt.END
            }

        container.addView(
            title
        )

        container.addView(
            competition
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

                /*
                 * Antes estaba CENTER_HORIZONTAL.
                 *
                 * Ahora se coloca en la esquina superior derecha
                 * y evita tapar el nombre/estado central del jugador.
                 */
                gravity =
                    Gravity.TOP or
                        Gravity.END

                x =
                    dp(10)

                y =
                    dp(105)
            }

        windowManager.addView(
            container,
            params
        )

        card =
            container

        titleView =
            title

        competitionView =
            competition

        nameView =
            name

        statsView =
            stats

        statusView =
            status
    }
}
