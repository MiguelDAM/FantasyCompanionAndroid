package com.example.fantasycompanion

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "Fantasy Companion"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, dp(16))
        })

        root.addView(TextView(this).apply {
            text = "V2 beta · LALIGA Fantasy Android\n\n" +
                "1. Activa el servicio de accesibilidad.\n" +
                "2. Abre LALIGA Fantasy.\n" +
                "3. Entra en la ficha de un jugador.\n" +
                "4. La tarjeta flotante aparecerá arriba.\n\n" +
                "La V2 mejora la detección del nombre y consulta estadísticas públicas de LALIGA. " +
                "Si una estadística no está disponible, se mostrará un guion en lugar de inventar datos."
            textSize = 17f
        })

        root.addView(Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, dp(24))
        })

        root.addView(Button(this).apply {
            text = "Activar Fantasy Companion"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        root.addView(Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(1, dp(16))
        })

        root.addView(TextView(this).apply {
            text = "Privacidad: el servicio está limitado al paquete de LALIGA Fantasy " +
                "(com.lfp.laligafantasy). No pulsa botones, no introduce texto y no guarda " +
                "credenciales ni contenido de otras aplicaciones."
            textSize = 13f
        })

        setContentView(root)
    }
}
