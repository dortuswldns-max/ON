package com.example.oppanavi

import org.mapsforge.core.model.LatLong

interface RouteSource {
    fun prepare(callback: (PrepareResult) -> Unit)
    fun calculateRoute(
        start: LatLong,
        destination: LatLong,
        callback: (RouteResult?) -> Unit
    )
    fun release()
}

data class PrepareResult(
    val success: Boolean,
    val errorMessage: String? = null
)

data class RouteResult(
    val points: List<LatLong>,
    val distanceMeters: Double? = null,
    val estimatedTimeSec: Long? = null
)
