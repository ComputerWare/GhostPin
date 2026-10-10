package org.computerware.ghostpin

import com.google.android.gms.maps.model.LatLng
import java.util.Arrays
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Fix(val position: LatLng, val bearing: Float)

/** Turns a polyline into "where am I after travelling X meters?". */
class RouteSim(private val points: List<LatLng>) {

    private val cumulative = DoubleArray(points.size)
    val totalMeters: Double

    init {
        require(points.size >= 2) { "Route needs at least two points" }
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i])
        }
        totalMeters = cumulative.last()
    }

    fun at(meters: Double): Fix {
        val d = meters.coerceIn(0.0, totalMeters)
        var i = Arrays.binarySearch(cumulative, d)
        if (i < 0) i = -(i + 1) - 1
        i = i.coerceIn(0, points.size - 2)

        val a = points[i]
        val b = points[i + 1]
        val segment = cumulative[i + 1] - cumulative[i]
        val t = if (segment > 0) ((d - cumulative[i]) / segment).coerceIn(0.0, 1.0) else 0.0

        return Fix(
            LatLng(
                a.latitude + (b.latitude - a.latitude) * t,
                a.longitude + (b.longitude - a.longitude) * t
            ),
            bearing(a, b)
        )
    }

    private fun distance(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
                sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearing(a: LatLng, b: LatLng): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}