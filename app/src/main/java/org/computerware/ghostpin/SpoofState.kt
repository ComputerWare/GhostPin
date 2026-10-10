package org.computerware.ghostpin

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.roundToInt

/** A road route: decoded polyline plus totals reported by the routing server. */
data class DriveRoute(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/** What the mock-location service is currently doing. The UI observes this. */
sealed interface SpoofStatus {
    data object Idle : SpoofStatus
    /** Holding a single fixed position. */
    data class Running(val target: LatLng) : SpoofStatus
    /** Simulating a drive along a route. */
    data class Driving(
        val position: LatLng,
        val progress: Float,
        val remainingMeters: Double,
        val speedKmh: Int
    ) : SpoofStatus
    /** Android refused the test provider: this app isn't the selected mock location app. */
    data object NotMockApp : SpoofStatus
    data class Failed(val message: String) : SpoofStatus
}

/** Process-wide state shared between the service (writer) and the UI (reader). */
object SpoofState {
    private val _status = MutableStateFlow<SpoofStatus>(SpoofStatus.Idle)
    val status: StateFlow<SpoofStatus> = _status.asStateFlow()

    fun set(status: SpoofStatus) {
        _status.value = status
    }

    /** Route handed from the UI to the service (too big for an Intent extra). */
    @Volatile
    var pendingRoute: DriveRoute? = null
}

fun LatLng.pretty(): String =
    String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

fun formatDistance(meters: Double): String =
    if (meters >= 1000) String.format(Locale.US, "%.1f km", meters / 1000)
    else String.format(Locale.US, "%.0f m", meters)

fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).roundToInt()
    return when {
        mins < 1 -> "<1 min"
        mins < 60 -> "$mins min"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}