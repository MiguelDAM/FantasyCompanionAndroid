package com.example.fantasycompanion

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FantasyAccessibilityService :
    AccessibilityService() {

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

    private var currentDetectionKey:
        String? = null

    /*
     * También comprobamos periódicamente si seguimos
     * en una ficha aunque no llegue un evento de accesibilidad.
     */
    private val activeWindowCheck =
        object : Runnable {

            override fun run() {

                if (
                    !::overlay.isInitialized
                ) {

                    mainHandler.postDelayed(
                        this,
                        500
                    )

                    return
                }

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

                    closePlayerOverlay()

                } else {

                    val detection =
                        PlayerDetector.detect(
                            root
                        )

                    /*
                     * Seguimos dentro de LALIGA Fantasy,
                     * pero hemos salido de la ficha.
                     */
                    if (
                        !detection.isPlayerProfile
                    ) {

                        closePlayerOverlay()
                    }
                }

                mainHandler.postDelayed(
                    this,
                    500
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
            "Fantasy Companion conectado"
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

            closePlayerOverlay()

            return
        }

        val root =
            rootInActiveWindow
                ?: return

        val detection =
            PlayerDetector.detect(
                root
            )

        /*
         * CLAVE:
         *
         * no enseñamos absolutamente nada
         * si no estamos en una ficha.
         */
        if (
            !detection.isPlayerProfile
        ) {

            closePlayerOverlay()

            return
        }

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
                "FICHA DETECTADA"
            )

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
         * Ya no exigimos score >= 5.
         *
         * La API de LALIGA es nuestro segundo filtro:
         * un texto puede llegar aquí con score bajo y aun así
         * ser exactamente "Mbappé" o "Camara".
         */
        val candidates =
            detection
                .candidates
                .map {
                    it.value
                }
                .distinct()
                .take(24)

        if (
            candidates.isEmpty()
        ) {

            closePlayerOverlay()

            return
        }

        val detectionKey =
            candidates
                .joinToString(
                    separator = "||"
                )

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
            "ENVIANDO A API: " +
                candidates.joinToString(" | ")
        )

        repository.get(
            candidateNames =
                candidates
        ) { stats ->

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

    private fun closePlayerOverlay() {

        currentDetectionKey =
            null

        if (
            ::overlay.isInitialized
        ) {

            overlay.hide()
        }
    }

    override fun onInterrupt() {

        closePlayerOverlay()
    }

    override fun onDestroy() {

        mainHandler.removeCallbacks(
            activeWindowCheck
        )

        closePlayerOverlay()

        super.onDestroy()
    }
}
