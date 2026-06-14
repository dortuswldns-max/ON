package com.example.oppanavi

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.mapsforge.core.model.LatLong

class AppLocationManager(
    private val context: Context,
    private val onLocationUpdate: (Location) -> Unit,
    private val onProviderDisabled: () -> Unit,
    private val onProviderEnabled: () -> Unit
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // smoothing 버퍼
    private val locationBuffer = mutableListOf<Location>()
    private val BUFFER_SIZE = 3
    private val MAX_SPEED_MS = 20.0
    private var lastValidLocation: Location? = null

    // GPS 신호 품질 판단 기준
    private val GPS_GOOD_ACCURACY = 15f   // 15m 이내 = GPS 양호
    private val MAX_ACCURACY_M = 50f      // 50m 초과 = 무시

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location, isGps = true)
        }
        override fun onProviderDisabled(provider: String) { onProviderDisabled() }
        override fun onProviderEnabled(provider: String) { onProviderEnabled() }
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location, isGps = false)
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }

    private fun handleLocation(location: Location, isGps: Boolean) {
        // GPS 신호 양호하면 네트워크 위치 무시
        if (!isGps) {
            lastValidLocation?.let { last ->
                if (last.provider == LocationManager.GPS_PROVIDER
                    && last.accuracy <= GPS_GOOD_ACCURACY
                    && System.currentTimeMillis() - last.time < 3000
                ) {
                    return  // GPS 잘 잡히는 중 → 기지국 무시!
                }
            }
        }

        val filtered = filterAndSmooth(location)
        if (filtered != null) {
            onLocationUpdate(filtered)
        }
    }

    private fun filterAndSmooth(location: Location): Location? {
        // 정확도 필터
        if (location.accuracy > MAX_ACCURACY_M) return null

        // spike 필터
        lastValidLocation?.let { prev ->
            val timeDiffSec = (location.time - prev.time) / 1000.0
            if (timeDiffSec > 0) {
                val distance = prev.distanceTo(location)
                val speedMs = distance / timeDiffSec
                if (speedMs > MAX_SPEED_MS) return null
            }
        }

        // 버퍼에 추가
        locationBuffer.add(location)
        if (locationBuffer.size > BUFFER_SIZE) {
            locationBuffer.removeAt(0)
        }

        // smoothing
        val smoothed = Location(location.provider ?: "gps")
        smoothed.latitude = locationBuffer.map { it.latitude }.average()
        smoothed.longitude = locationBuffer.map { it.longitude }.average()
        smoothed.accuracy = locationBuffer.map { it.accuracy }.average().toFloat()
        smoothed.time = location.time
        smoothed.speed = location.speed
        smoothed.bearing = location.bearing

        lastValidLocation = location
        return smoothed
    }

    fun startUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 2f, gpsListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 5f, networkListener
                )
            }
        } catch (e: SecurityException) { }
    }

    fun stopUpdates() {
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(networkListener)
        locationBuffer.clear()
        lastValidLocation = null
    }

    fun getLastKnownLocation(): LatLong? {
        return try {
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            last?.let { LatLong(it.latitude, it.longitude) }
        } catch (e: SecurityException) { null }
    }
}