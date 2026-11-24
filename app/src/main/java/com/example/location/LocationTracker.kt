package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.example.data.model.LocationPayload
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationTracker(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
    private val systemLocationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(isLive: Boolean = false, durationMs: Long = 0L): LocationPayload? {
        return suspendCancellableCoroutine { cont ->
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc: Location? ->
                    if (loc != null) {
                        cont.resume(loc.toPayload(isLive, durationMs))
                    } else {
                        val fallback = getLastKnownSystemLocation()
                        cont.resume(fallback?.toPayload(isLive, durationMs))
                    }
                }
                .addOnFailureListener {
                    val fallback = getLastKnownSystemLocation()
                    cont.resume(fallback?.toPayload(isLive, durationMs))
                }
        }
    }

    @SuppressLint("MissingPermission")
    fun liveLocationStream(durationMs: Long): Flow<LocationPayload> = callbackFlow {
        val expiresAt = System.currentTimeMillis() + durationMs
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 8_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setDurationMillis(durationMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    LocationPayload(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracy,
                        isLive = true,
                        expiresAtMs = expiresAt,
                        label = "Live GPS Location"
                    )
                )
            }
        }

        val fusedStarted = runCatching {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        }.getOrDefault(false)

        var sysListener: LocationListener? = null
        if (!fusedStarted) {
            val listener = LocationListener { loc ->
                trySend(
                    LocationPayload(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracyMeters = loc.accuracy,
                        isLive = true,
                        expiresAtMs = expiresAt,
                        label = "Live GPS Location"
                    )
                )
            }
            sysListener = listener
            runCatching {
                systemLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    8_000L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }

        awaitClose {
            runCatching { fusedClient.removeLocationUpdates(callback) }
            sysListener?.let { runCatching { systemLocationManager.removeUpdates(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownSystemLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            val loc = runCatching { systemLocationManager.getLastKnownLocation(provider) }.getOrNull()
            if (loc != null) return loc
        }
        return null
    }

    private fun Location.toPayload(isLive: Boolean, durationMs: Long): LocationPayload {
        return LocationPayload(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            isLive = isLive,
            expiresAtMs = if (isLive && durationMs > 0L) System.currentTimeMillis() + durationMs else 0L,
            label = if (isLive) "Live GPS Location" else "Shared Location"
        )
    }
}
