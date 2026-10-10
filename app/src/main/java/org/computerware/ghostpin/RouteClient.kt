package org.computerware.ghostpin

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Fetches a driving route that follows real roads.
 *
 * Uses the public OSRM demo server (no API key). It's meant for light use; if you
 * publish this app widely, host your own OSRM or swap this one function for another
 * routing provider (Google Routes, Mapbox, GraphHopper...). Nothing else needs to change.
 */
object RouteClient {

    private const val BASE_URL = "https://router.project-osrm.org/route/v1/driving"

    suspend fun fetch(start: LatLng, end: LatLng): DriveRoute = withContext(Dispatchers.IO) {
        val url = URL(
            String.format(
                Locale.US,
                "%s/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=false",
                BASE_URL, start.longitude, start.latitude, end.longitude, end.latitude
            )
        )

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "GhostPin/1.0 (Android)")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()

            val status = json?.optString("code").orEmpty()
            if (json == null || status != "Ok") {
                throw IOException(
                    when (status) {
                        "NoRoute" -> "No drivable route between those points"
                        "NoSegment" -> "One of the points isn't near a road"
                        else -> json?.optString("message")?.takeIf { it.isNotEmpty() }
                            ?: "Routing server error ($code)"
                    }
                )
            }

            val route = json.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<LatLng>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                points.add(LatLng(c.getDouble(1), c.getDouble(0)))
            }
            if (points.size < 2) throw IOException("Start and destination are too close together")

            DriveRoute(points, route.getDouble("distance"), route.getDouble("duration"))
        } finally {
            conn.disconnect()
        }
    }
}