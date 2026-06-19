package com.example.oppanavi

import android.os.Handler
import android.os.Looper
import org.mapsforge.core.model.LatLong

enum class RouteState {
    IDLE,
    LOADING,
    CALCULATING,
    READY,
    RECALCULATING,
    ERROR
}

class RouteManager(
    private val routeSource: RouteSource,
    private val debugLogger: DebugLogger
) {

    var state: RouteState = RouteState.IDLE
        private set

    var lastError: String? = null
        private set

    var currentRoute: RouteResult? = null
        private set

    var isReady: Boolean = false
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(onReady: () -> Unit = {}, onError: (String?) -> Unit = {}) {
        state = RouteState.LOADING
        lastError = null

        routeSource.prepare { result ->
            mainHandler.post {
                if (result.success) {
                    isReady = true
                    state = RouteState.IDLE
                    onReady()
                } else {
                    isReady = false
                    state = RouteState.ERROR
                    lastError = result.errorMessage
                    onError(result.errorMessage)
                }
            }
        }
    }

    fun retryPrepare(onReady: () -> Unit = {}, onError: (String?) -> Unit = {}) {
        initialize(onReady, onError)
    }

    fun calculateRoute(
        start: LatLong,
        destination: LatLong,
        callback: (RouteResult?) -> Unit
    ) {
        debugLogger.routeRequest(start.toString(), destination.toString())

        if (!isReady) {
            state = RouteState.ERROR
            debugLogger.routeFailed("not ready")
            mainHandler.post { callback(null) }
            return
        }

        state = if (currentRoute == null) RouteState.CALCULATING else RouteState.RECALCULATING

        routeSource.calculateRoute(start, destination) { result ->
            mainHandler.post {
                if (result != null) {
                    currentRoute = result
                    state = RouteState.READY
                    debugLogger.routeSuccess(result.distanceMeters, result.estimatedTimeSec, result.points.size)
                } else {
                    state = RouteState.ERROR
                    debugLogger.routeFailed(lastError)
                }
                callback(result)
            }
        }
    }

    fun clearRoute() {
        debugLogger.routeClear()
        currentRoute = null
        state = if (isReady) RouteState.IDLE else RouteState.LOADING
    }

    fun release() {
        routeSource.release()
    }
}