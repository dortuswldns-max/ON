package com.example.oppanavi

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.mapsforge.core.model.LatLong
import kotlin.math.abs

class AppLocationManager(
    private val context: Context,
    private val onLocationUpdate: (Location) -> Unit,
    private val onProviderDisabled: () -> Unit,
    private val onProviderEnabled: () -> Unit
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // smoothing 버퍼 (최근 3개 위치)
    private val locationBuffer = mutableListOf<Location>()
    private val BUFFER_SIZE = 3

    // spike 제거 기준
    private val MAX_SPEED_MS = 20.0  // 72km/h 이상이면 spike로 판단
    private val MAX_ACCURACY_M = 50f // 정확도 50m 초과면 무시

    private var lastValidLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val filtered = filterAndSmooth(location)
            if (filtered != null) {
                onLocationUpdate(filtered)
            }
        }
        override fun onProviderDisabled(provider: String) {
            onProviderDisabled()
        }
        override fun onProviderEnabled(provider: String) {
            onProviderEnabled()
        }
    }

    private fun filterAndSmooth(location: Location): Location? {
        // 1. 정확도 필터 — 50m 초과면 무시
        if (location.accuracy > MAX_ACCURACY_M) return null

        // 2. spike 필터 — 이전 위치 대비 속도 계산
        lastValidLocation?.let { prev ->
            val timeDiffSec = (location.time - prev.time) / 1000.0
            if (timeDiffSec > 0) {
                val distance = prev.distanceTo(location)
                val speedMs = distance / timeDiffSec
                if (speedMs > MAX_SPEED_MS) return null  // spike 제거
            }
        }

        // 3. 버퍼에 추가
        locationBuffer.add(location)
        if (locationBuffer.size > BUFFER_SIZE) {
            locationBuffer.removeAt(0)
        }

        // 4. smoothing — 버퍼 평균 위치 계산
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
                    LocationManager.GPS_PROVIDER, 1000L, 2f, locationListener
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener
                )
            }
        } catch (e: SecurityException) { }
    }

    fun stopUpdates() {
        locationManager.removeUpdates(locationListener)
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