package com.example.oppanavi

import org.mapsforge.core.model.LatLong

data class NavigationState(
    val currentPosition: LatLong,
    val nearestRoutePoint: LatLong,
    val nearestIndex: Int,
    val distanceToRoute: Double,
    val remainingDistance: Double,
    val progressPercent: Double,
    val isOffRoute: Boolean,
    val offRouteCount: Int
)

class NavigationEngine(
    private val debugLogger: DebugLogger
) {

    private var currentRoute: RouteResult? = null
    private var nearestIndex = 0
    private var cumulativeDistances: List<Double> = emptyList()
    private var offRouteCount = 0
    private val OFF_ROUTE_DISTANCE_METERS = 30.0
    private val OFF_ROUTE_COUNT_THRESHOLD = 3

    fun setRoute(route: RouteResult) {
        currentRoute = route
        nearestIndex = 0
        offRouteCount = 0
        cumulativeDistances = buildCumulativeDistances(route.points)
        debugLogger.navRouteSet(route.points.size)
    }

    fun updateLocation(current: LatLong): NavigationState? {
        val route = currentRoute ?: return null
        val points = route.points
        if (points.isEmpty()) return null

        var minDist = Double.MAX_VALUE
        var nearestIdx = 0
        for (i in points.indices) {
            val d = haversine(current, points[i])
            if (d < minDist) {
                minDist = d
                nearestIdx = i
            }
        }
        nearestIndex = nearestIdx

        if (minDist > OFF_ROUTE_DISTANCE_METERS) {
            offRouteCount++
        } else {
            offRouteCount = 0
        }
        val isOffRoute = offRouteCount >= OFF_ROUTE_COUNT_THRESHOLD
        if (isOffRoute) {
            debugLogger.navOffRoute(offRouteCount, minDist)
        }

        val remaining = remainingDistanceFrom(nearestIdx)
        val totalDistance = cumulativeDistances.lastOrNull() ?: 0.0
        val progressPercent = if (totalDistance > 0) {
            ((totalDistance - remaining) / totalDistance * 100).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        debugLogger.navUpdate(nearestIdx, minDist, remaining, progressPercent)

        return NavigationState(
            currentPosition = current,
            nearestRoutePoint = points[nearestIdx],
            nearestIndex = nearestIdx,
            distanceToRoute = minDist,
            remainingDistance = remaining,
            progressPercent = progressPercent,
            isOffRoute = isOffRoute,
            offRouteCount = offRouteCount
        )
    }

    fun clearRoute() {
        debugLogger.navClear()
        currentRoute = null
        nearestIndex = 0
        offRouteCount = 0
        cumulativeDistances = emptyList()
    }

    private fun buildCumulativeDistances(points: List<LatLong>): List<Double> {
        if (points.size < 2) return List(points.size) { 0.0 }

        val distances = MutableList(points.size) { 0.0 }
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += haversine(points[i - 1], points[i])
            distances[i] = acc
        }
        return distances
    }

    private fun remainingDistanceFrom(index: Int): Double {
        val total = cumulativeDistances.lastOrNull() ?: return 0.0
        val current = cumulativeDistances.getOrElse(index) { total }
        return (total - current).coerceAtLeast(0.0)
    }

    private fun haversine(a: LatLong, b: LatLong): Double {
        val r = 6371000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x))
    }
}