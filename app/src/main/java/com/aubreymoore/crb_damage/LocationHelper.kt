package com.aubreymoore.crb_damage

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*

class LocationHelper(context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    var lastLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Update the coordinate whenever the sensor sees a change
            lastLocation = result.lastLocation
            android.util.Log.d("GPS_DEBUG", "Update: ${lastLocation?.latitude}, ${lastLocation?.longitude}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
// Request immediate "last known" location to prime the pump
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) lastLocation = location
        }

        // Setup high-frequency requests (every 1 second)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L) // Allow updates as fast as twice a second
            .setWaitForAccurateLocation(false)
            .build()

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun getLatitude() = lastLocation?.latitude
    fun getLongitude() = lastLocation?.longitude
}