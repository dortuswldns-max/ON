package com.example.oppanavi

import org.mapsforge.core.model.LatLong
import java.util.concurrent.Executors

class GraphHopperRouteSource(
    private val graphHopperModule: GraphHopperModule,
    private val debugLogger: DebugLogger
) : RouteSource {

    private val routeExecutor = Executors.newSingleThreadExecutor()

    override fun prepare(callback: (PrepareResult) -> Unit) {
        graphHopperModule.onReady = {
            callback(PrepareResult(success = true))
        }

        graphHopperModule.onError = { msg ->
            callback(
                PrepareResult(
                    success = false,
                    errorMessage = msg
                )
            )
        }

        graphHopperModule.initialize()
    }

    override fun calculateRoute(
        start: LatLong,
        destination: LatLong,
        callback: (RouteResult?) -> Unit
    ) {
        debugLogger.ghCalculateStart()
        routeExecutor.execute {
            val info: GHRouteInfo? = try {
                graphHopperModule.routeWithDetails(start, destination)
            } catch (e: Exception) {
                debugLogger.ghCalculateFailed(e.message)
                null
            }

            val result = info?.let {
                debugLogger.ghCalculateSuccess(it.points.size, it.distanceMeters, it.timeSec)
                RouteResult(
                    points = it.points,
                    distanceMeters = it.distanceMeters,
                    estimatedTimeSec = it.timeSec
                )
            }

            if (info == null) {
                debugLogger.ghCalculateFailed("routeWithDetails returned null")
            }

            callback(result)
        }
    }

    override fun release() {
        graphHopperModule.release()
    }
}