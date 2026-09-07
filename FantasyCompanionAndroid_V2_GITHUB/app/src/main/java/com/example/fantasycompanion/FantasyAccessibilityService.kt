package com.example.fantasycompanion

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FantasyAccessibilityService : AccessibilityService() {

    companion object {

        private const val TARGET_PACKAGE =
            "com.lfp.laligafantasy"

        private const val TAG =
            "FantasyUI"
    }

    private lateinit var overlay:
        OverlayController

    private val repository =
        LaLigaStatsRepository()

    private val mainHandler =
        Handler(
            Looper.getMainLooper()
        )

    private var lastDumpAt =
        0L

    /*
     * Ya no guardamos solo "currentPlayer".
     *
     * Guardamos una firma de los candidatos visibles,
     * porque todavía no sabemos cuál de ellos es el jugador.
     */
    private var currentDetectionKey:
        String? = null

    private val activeWindowCheck =
        object : Runnable {

            override fun run() {

                val root =
                    rootInActiveWindow

                val packageName =
                    root
                        ?.packageName
                        ?.toString()

                if (
                    packageName !=
                    TARGET_PACKAGE
                ) {

                    currentDetectionKey =
                        null

                    if (
                        ::overlay.isInitialized
                    ) {

                        overlay.hide()
                    }
                }

                mainHandler.postDelayed(
                    this,
                    700
                )
            }
        }

    override fun onServiceConnected() {

        super.onServiceConnected()

        overlay =
            OverlayController(
                this
            )

        mainHandler.post(
            activeWindowCheck
        )

        Log.i(
            TAG,
            "Fantasy Companion V2 conectado"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        if (
            !::overlay.isInitialized ||
            event == null
        ) {
            return
        }

        if (
            event.packageName
                ?.toString() !=
            TARGET_PACKAGE
        ) {
            return
        }

        val root =
            rootInActiveWindow
                ?: return

        val detection =
            PlayerDetector.detect(
                root
            )

        val now =
            System.currentTimeMillis()

        if (
            now - lastDumpAt >
            1800
        ) {

            lastDumpAt =
                now

            Log.d(
                TAG,
                "TEXTOS VISIBLES: " +
                    detection
                        .visibleStrings
                        .joinToString(" | ")
            )

            Log.d(
                TAG,
                "CANDIDATOS: " +
                    detection
                        .candidates
                        .joinToString {

                            "${it.value}(${it.score})"
                        }
            )
        }

        /*
         * Antes:
         *
         * val playerName = detection.playerName
         *
         * Eso era el problema:
         * si el detector escogía FC Barcelona,
         * buscábamos FC Barcelona como jugador.
         *
         *
         * AHORA:
         *
         * enviamos varios candidatos al repository.
         */
        val candidates =
            detection
                .candidates
                .filter {
                    it.score >= 5
                }
                .map {
                    it.value
                }
                .distinct()
                .take(8)

        if (
            candidates.isEmpty()
        ) {

            currentDetectionKey =
                null

            overlay.hide()

            return
        }

        val detectionKey =
            candidates
                .joinToString(
                    separator = "||"
                )

        /*
         * Evitar relanzar la misma consulta en cada evento
         * de accesibilidad.
         */
        if (
            detectionKey ==
            currentDetectionKey
        ) {
            return
        }

        currentDetectionKey =
            detectionKey

        Log.d(
            TAG,
            "ENVIANDO CANDIDATOS API: " +
                candidates.joinToString(" | ")
        )

        repository.get(
            candidateNames =
                candidates
        ) { stats ->

            /*
             * Solo mostramos la respuesta si seguimos
             * viendo la misma ficha.
             */
            if (
                currentDetectionKey ==
                detectionKey &&
                ::overlay.isInitialized
            ) {

                overlay.show(
                    stats
                )
            }
        }
    }

    override fun onInterrupt() {

        currentDetectionKey =
            null

        if (
            ::overlay.isInitialized
        ) {

            overlay.hide()
        }
    }

    override fun onDestroy() {

        mainHandler.removeCallbacks(
            activeWindowCheck
        )

        currentDetectionKey =
            null

        if (
            ::overlay.isInitialized
        ) {

            overlay.hide()
        }

        super.onDestroy()
    }
}
