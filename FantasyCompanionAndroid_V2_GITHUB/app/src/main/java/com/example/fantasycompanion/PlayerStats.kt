package com.example.fantasycompanion

data class PlayerStats(
    val playerName: String,
    val goals: Int? = null,
    val assists: Int? = null,
    val yellowCards: Int? = null,
    val redCards: Int? = null,
    val cleanSheets: Int? = null,
    val teamName: String? = null,
    val competition: String? = null,
    val source: String? = null,
    val loading: Boolean = false,
    val error: String? = null
)
