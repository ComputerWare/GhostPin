package org.computerware.ghostpin

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.android.gms.maps.model.LatLng

/**
 * Thin wrapper around LocationManager's test-provider API.
 * Registers both GPS and NETWORK providers so apps listening to either see the mock fix.
 */
class MockLocationEngine(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    var running = false
        private set

    /** @throws SecurityException if this app is not the selected mock location app. */
    fun start() {
        try {
            providers.forEach(::register)
            running = true
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun register(provider: String) {
        // Clear a leftover provider from a previous run/crash; harmless if absent.
        runCatching { lm.removeTestProvider(provider) }
        lm.addTestProvider(
            provider,
            /* requiresNetwork */ false,
            /* requiresSatellite */ false,
            /* requiresCell */ false,
            /* hasMonetaryCost */ false,
            /* supportsAltitude */ true,
            /* supportsSpeed */ true,
            /* supportsBearing */ true,
            Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE
        )
        lm.setTestProviderEnabled(provider, true)
    }

    fun push(target: LatLng, bearingDeg: Float = 0f, speedMps: Float = 0f) {
        providers.forEach { provider ->
            val fix = Location(provider).apply {
                latitude = target.latitude
                longitude = target.longitude
                altitude = 0.0
                accuracy = 3f
                speed = speedMps
                bearing = bearingDeg
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            lm.setTestProviderLocation(provider, fix)
        }
    }

    fun stop() {
        providers.forEach { p ->
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        running = false
    }
}