package com.example.oppanavi

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.widget.Toast
import org.mapsforge.core.model.LatLong

class AppLocationManager(
    private val context: Context,
    private val onLocationUpdate: (Location) -> Unit,
    private val onProviderDisabled: () -> Unit,
    private val onProviderEnabled: () -> Unit
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocationUpdate(location)
        }
        override fun onProviderDisabled(provider: String) {
            onProviderDisabled()
        }
        override fun onProviderEnabled(provider: String) {
            onProviderEnabled()
        }
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