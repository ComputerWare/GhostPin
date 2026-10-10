package org.computerware.ghostpin

import android.app.Application
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Mode { Pin, Drive }
enum class Endpoint { Start, End }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Popups -----------------------------------------------------------------------

    private val prefs = Prefs(app)

    private val _dialog = MutableStateFlow(pickStartupDialog())
    val dialog: StateFlow<AppDialog> = _dialog.asStateFlow()

    /**
     * First launch: setup guide.
     * Later: the coffee prompt on the 3rd launch, then every 5th launch after that.
     */
    private fun pickStartupDialog(): AppDialog {
        val launches = prefs.launchCount + 1
        prefs.launchCount = launches

        if (!prefs.setupSeen) return AppDialog.Setup

        val due = launches >= 3 && launches - prefs.lastCoffeeLaunch >= 5
        if (due) {
            prefs.lastCoffeeLaunch = launches
            return AppDialog.Coffee
        }
        return AppDialog.None
    }

    fun showSetup() { _dialog.value = AppDialog.Setup }

    fun dismissDialog() {
        if (_dialog.value == AppDialog.Setup) prefs.setupSeen = true
        _dialog.value = AppDialog.None
    }

    // ---- State ------------------------------------------------------------------------

    private val _mode = MutableStateFlow(Mode.Pin)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _pin = MutableStateFlow<LatLng?>(null)
    val pin: StateFlow<LatLng?> = _pin.asStateFlow()

    private val _driveStart = MutableStateFlow<LatLng?>(null)
    val driveStart: StateFlow<LatLng?> = _driveStart.asStateFlow()

    private val _driveEnd = MutableStateFlow<LatLng?>(null)
    val driveEnd: StateFlow<LatLng?> = _driveEnd.asStateFlow()

    /** Which endpoint the next map tap / search result will set. */
    private val _editing = MutableStateFlow(Endpoint.Start)
    val editing: StateFlow<Endpoint> = _editing.asStateFlow()

    private val _route = MutableStateFlow<DriveRoute?>(null)
    val route: StateFlow<DriveRoute?> = _route.asStateFlow()

    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()

    private val _speed = MutableStateFlow(50)
    val speed: StateFlow<Int> = _speed.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _camera = MutableSharedFlow<LatLng>(extraBufferCapacity = 1)
    val camera: SharedFlow<LatLng> = _camera.asSharedFlow()

    private val _bounds = MutableSharedFlow<LatLngBounds>(extraBufferCapacity = 1)
    val bounds: SharedFlow<LatLngBounds> = _bounds.asSharedFlow()

    private var routeJob: Job? = null
    private var routeRequestId = 0

    // ---- Mode / endpoints -------------------------------------------------------------

    fun setMode(m: Mode) { _mode.value = m }

    fun selectEndpoint(e: Endpoint) { _editing.value = e }

    fun onQueryChange(q: String) { _query.value = q }

    fun onMapClick(latLng: LatLng) { place(latLng) }

    /** Applies a chosen point according to the current mode. Returns false if rejected. */
    private fun place(p: LatLng): Boolean {
        when (_mode.value) {
            Mode.Pin -> _pin.value = p
            Mode.Drive -> {
                if (SpoofState.status.value is SpoofStatus.Driving) {
                    _messages.tryEmit("Stop the current drive to change the route")
                    return false
                }
                when (_editing.value) {
                    Endpoint.Start -> {
                        _driveStart.value = p
                        _editing.value = Endpoint.End
                    }
                    Endpoint.End -> _driveEnd.value = p
                }
                _route.value = null
                fetchRouteIfReady()
            }
        }
        return true
    }

    private fun moveTo(p: LatLng) {
        if (place(p)) _camera.tryEmit(p)
    }

    fun clearRoute() {
        routeRequestId++
        routeJob?.cancel()
        _routing.value = false
        _driveStart.value = null
        _driveEnd.value = null
        _route.value = null
        _editing.value = Endpoint.Start
    }

    // ---- Routing ----------------------------------------------------------------------

    private fun fetchRouteIfReady() {
        val start = _driveStart.value
        val end = _driveEnd.value
        val id = ++routeRequestId
        routeJob?.cancel()

        if (start == null || end == null) {
            _routing.value = false
            return
        }

        routeJob = viewModelScope.launch {
            _routing.value = true
            try {
                val result = RouteClient.fetch(start, end)
                _route.value = result

                val builder = LatLngBounds.Builder()
                result.points.forEach { builder.include(it) }
                _bounds.tryEmit(builder.build())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _route.value = null
                _messages.tryEmit("Couldn't get a route: ${e.message ?: "network error"}")
            } finally {
                if (id == routeRequestId) _routing.value = false
            }
        }
    }

    // ---- Start / stop -----------------------------------------------------------------

    fun startSpoofing() {
        val target = _pin.value
        if (target == null) {
            _messages.tryEmit("Drop a pin first")
            return
        }
        MockLocationService.start(getApplication(), target)
    }

    fun startDrive() {
        val r = _route.value
        if (r == null) {
            _messages.tryEmit("Pick a start and destination first")
            return
        }
        SpoofState.pendingRoute = r
        MockLocationService.startDrive(getApplication(), _speed.value)
    }

    fun stopSpoofing() {
        MockLocationService.stop(getApplication())
    }

    fun onSpeedChange(kmh: Int) { _speed.value = kmh }

    /** Called when the slider is released: pushes the new speed to a drive in progress. */
    fun commitSpeed() {
        if (SpoofState.status.value is SpoofStatus.Driving) {
            MockLocationService.setSpeed(getApplication(), _speed.value)
        }
    }

    // ---- Search -----------------------------------------------------------------------

    fun search() {
        val q = _query.value.trim()
        if (q.isEmpty()) return

        // Allow typing raw coordinates, e.g. "48.8584, 2.2945"
        parseCoordinates(q)?.let { moveTo(it); return }

        viewModelScope.launch {
            _searching.value = true
            val result = geocode(q)
            _searching.value = false
            result
                .onSuccess { addr ->
                    if (addr == null) _messages.tryEmit("Address not found")
                    else moveTo(LatLng(addr.latitude, addr.longitude))
                }
                .onFailure { _messages.tryEmit("Search failed: ${it.message ?: "unknown error"}") }
        }
    }

    private fun parseCoordinates(text: String): LatLng? {
        val m = COORDS.matchEntire(text) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
    }

    private suspend fun geocode(query: String): Result<Address?> = withContext(Dispatchers.IO) {
        runCatching {
            check(Geocoder.isPresent()) { "No geocoder available on this device" }
            val geocoder = Geocoder(getApplication(), Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            cont.resumeWithException(IOException(errorMessage ?: "Geocoder error"))
                        }
                    })
                }
            } else {
                geocodeLegacy(geocoder, query)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun geocodeLegacy(geocoder: Geocoder, query: String): Address? =
        geocoder.getFromLocationName(query, 1)?.firstOrNull()

    private companion object {
        val COORDS = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    }
}