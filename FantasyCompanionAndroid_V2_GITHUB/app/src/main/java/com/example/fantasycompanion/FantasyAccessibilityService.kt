package com.example.fantasycompanion

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FantasyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TARGET_PACKAGE = "com.lfp.laligafantasy"
        private const val TAG = "FantasyUI"
    }

    private lateinit var overlay: OverlayController
    private val repository = LaLigaStatsRepository()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDumpAt = 0L
    private var currentPlayer: String? = null

    private val activeWindowCheck = object : Runnable {
        override fun run() {
            val root = rootInActiveWindow
            val packageName = root?.packageName?.toString()
            if (packageName != TARGET_PACKAGE) {
                currentPlayer = null
                overlay.hide()
            }
            mainHandler.postDelayed(this, 700)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        mainHandler.post(activeWindowCheck)
        Log.i(TAG, "Fantasy Companion V2 conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlay.isInitialized || event == null) return
        if (event.packageName?.toString() != TARGET_PACKAGE) return

        val root = rootInActiveWindow ?: return
        val detection = PlayerDetector.detect(root)

        val now = System.currentTimeMillis()
        if (now - lastDumpAt > 1800) {
            lastDumpAt = now
            Log.d(TAG, "TEXTOS VISIBLES: ${detection.visibleStrings.joinToString(" | ")}")
            Log.d(TAG, "CANDIDATOS: ${detection.candidates.joinToString { "${it.value}(${it.score})" }}")
        }

        val playerName = detection.playerName
        if (playerName == null) {
            currentPlayer = null
            overlay.hide()
            return
        }

        if (playerName == currentPlayer) return
        currentPlayer = playerName
        repository.get(playerName) { stats ->
            if (currentPlayer == playerName && ::overlay.isInitialized) {
                overlay.show(stats)
            }
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(activeWindowCheck)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }
}
