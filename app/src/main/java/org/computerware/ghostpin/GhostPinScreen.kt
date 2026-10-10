package org.computerware.ghostpin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import kotlin.math.roundToInt

@Composable
fun GhostPinScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val mode by vm.mode.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val driveStart by vm.driveStart.collectAsStateWithLifecycle()
    val driveEnd by vm.driveEnd.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val routing by vm.routing.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val status by SpoofState.status.collectAsStateWithLifecycle()

    var follow by remember { mutableStateOf(true) }

    val snackbar = remember { SnackbarHostState() }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(20.0, 0.0), 2f)
    }

    // One-shot events from the ViewModel
    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        vm.camera.collect { camera.animate(CameraUpdateFactory.newLatLngZoom(it, 15f)) }
    }
    LaunchedEffect(Unit) {
        vm.bounds.collect { camera.animate(CameraUpdateFactory.newLatLngBounds(it, 120)) }
    }

    // Keep the camera on the car while driving
    val drivingPos = (status as? SpoofStatus.Driving)?.position
    LaunchedEffect(drivingPos) {
        if (follow && drivingPos != null) {
            val zoom = if (camera.position.zoom < 14f) 16f else camera.position.zoom
            camera.animate(CameraUpdateFactory.newLatLngZoom(drivingPos, zoom), 800)
        }
    }

    // Notification permission (Android 13+) so the foreground notification is visible
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val doSearch = {
        keyboard?.hide()
        vm.search()
    }

    // ---- Popups ----
    when (dialog) {
        AppDialog.Setup -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Welcome to GhostPin") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Do these one-time steps so mock locations work:")
                    SETUP_STEPS.forEachIndexed { i, step -> Text("${i + 1}. $step") }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissDialog) { Text("Got it") } },
            dismissButton = {
                TextButton(onClick = { openDeveloperSettings(context) }) { Text("Developer options") }
            }
        )

        AppDialog.Coffee -> AlertDialog(
            onDismissRequest = vm::dismissDialog,
            title = { Text("Enjoying GhostPin? ☕") },
            text = {
                Text(
                    "GhostPin is free and made by one person. If it's been useful, " +
                            "consider buying me a coffee. It helps me keep improving it!"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openCoffeePage(context)
                    vm.dismissDialog()
                }) { Text("Buy me a coffee") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Maybe later") } }
        )

        AppDialog.None -> Unit
    }

    val routeColor = MaterialTheme.colorScheme.primary
    val bottomPad = if (mode == Mode.Drive) 330.dp else 230.dp

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            contentPadding = PaddingValues(top = 88.dp, bottom = bottomPad),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            onMapClick = vm::onMapClick
        ) {
            if (mode == Mode.Pin) {
                pin?.let { p ->
                    key(p) {
                        Marker(state = rememberMarkerState(position = p), title = "Selected pin")
                    }
                }
            } else {
                route?.let { Polyline(points = it.points, color = routeColor, width = 12f) }
                driveStart?.let { s ->
                    key(s) {
                        Marker(
                            state = rememberMarkerState(position = s),
                            title = "Start",
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                        )
                    }
                }
                driveEnd?.let { e ->
                    key(e) {
                        Marker(
                            state = rememberMarkerState(position = e),
                            title = "Destination",
                            icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)
                        )
                    }
                }
            }

            when (val s = status) {
                is SpoofStatus.Running -> LiveMarker(s.target, "Spoofed location")
                is SpoofStatus.Driving -> LiveMarker(s.position, "Car")
                else -> Unit
            }
        }

        // Search bar + snackbars
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(6.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (mode == Mode.Drive) {
                                    "Set ${if (editing == Endpoint.Start) "start (A)" else "destination (B)"}…"
                                } else {
                                    "Address, landmark or lat, lng"
                                },
                                maxLines = 1
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() })
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { doSearch() }, enabled = !searching) {
                        if (searching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Search")
                        }
                    }
                }
            }
            SnackbarHost(snackbar)
        }

        // Bottom control panel
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == Mode.Pin,
                        onClick = { vm.setMode(Mode.Pin) },
                        label = { Text("Pin") }
                    )
                    FilterChip(
                        selected = mode == Mode.Drive,
                        onClick = { vm.setMode(Mode.Drive) },
                        label = { Text("Drive") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::showSetup) { Text("Guide") }
                    TextButton(onClick = { openCoffeePage(context) }) { Text("☕") }
                }

                if (mode == Mode.Pin) {
                    Text(
                        text = pin?.pretty() ?: "Tap the map or search to drop a pin",
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editing == Endpoint.Start,
                            onClick = { vm.selectEndpoint(Endpoint.Start) },
                            label = {
                                Text(
                                    "A  " + (driveStart?.pretty() ?: "tap map"),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = editing == Endpoint.End,
                            onClick = { vm.selectEndpoint(Endpoint.End) },
                            label = {
                                Text(
                                    "B  " + (driveEnd?.pretty() ?: "tap map"),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val currentRoute = route
                    when {
                        routing -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Finding route…", style = MaterialTheme.typography.bodyMedium)
                        }
                        currentRoute != null -> Text(
                            "${formatDistance(currentRoute.distanceMeters)} · " +
                                    "${formatDuration(currentRoute.distanceMeters / (speed / 3.6))} at $speed km/h",
                            style = MaterialTheme.typography.titleMedium
                        )
                        else -> Text(
                            "Choose a start (A) and destination (B) by tapping the map or searching.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Speed: $speed km/h",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = follow,
                            onClick = { follow = !follow },
                            label = { Text("Follow") }
                        )
                        TextButton(
                            onClick = vm::clearRoute,
                            enabled = driveStart != null || driveEnd != null
                        ) { Text("Clear") }
                    }
                    Slider(
                        value = speed.toFloat(),
                        onValueChange = { vm.onSpeedChange(it.roundToInt()) },
                        onValueChangeFinished = vm::commitSpeed,
                        valueRange = 10f..130f
                    )
                }

                when (val s = status) {
                    is SpoofStatus.Running -> Text(
                        "Spoofing ${s.target.pretty()}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is SpoofStatus.Driving -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Driving · ${(s.progress * 100).roundToInt()}% · " +
                                    "${formatDistance(s.remainingMeters)} left · ${s.speedKmh} km/h",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    SpoofStatus.NotMockApp -> Column {
                        Text(
                            "GhostPin isn't the selected mock location app. Open Developer options " +
                                    "→ Select mock location app → GhostPin, then try again.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { openDeveloperSettings(context) }) {
                            Text("Open developer options")
                        }
                    }
                    is SpoofStatus.Failed -> Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    SpoofStatus.Idle -> Unit
                }

                val active = status is SpoofStatus.Running || status is SpoofStatus.Driving
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == Mode.Pin) {
                        Button(
                            onClick = vm::startSpoofing,
                            enabled = pin != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (status is SpoofStatus.Running) "Move here" else "Start")
                        }
                    } else {
                        Button(
                            onClick = vm::startDrive,
                            enabled = route != null && !routing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (status is SpoofStatus.Driving) "Restart drive" else "Start drive")
                        }
                    }
                    OutlinedButton(
                        onClick = vm::stopSpoofing,
                        enabled = active,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop")
                    }
                }
            }
        }
    }
}

/** Green marker that glides to new positions instead of being recreated every tick. */
@Composable
@GoogleMapComposable
private fun LiveMarker(position: LatLng, title: String) {
    val state = rememberMarkerState(position = position)
    LaunchedEffect(position) { state.position = position }
    val icon = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN) }
    Marker(state = state, title = title, icon = icon, zIndex = 1f)
}

private val SETUP_STEPS = listOf(
    "Turn on Developer options: Settings → About phone, then tap \"Build number\" 7 times.",
    "Open Settings → System → Developer options → \"Select mock location app\" and choose GhostPin. " +
            "(Tap \"Developer options\" below to jump there.)",
    "Allow notifications when asked. GhostPin runs as a foreground service, and the notification has a Stop button.",
    "Pin mode: tap the map, search an address, or type coordinates like 48.8584, 2.2945, then press Start.",
    "Drive mode: set a start (A) and destination (B) by tapping the map or searching. GhostPin finds a real " +
            "road route (needs internet), then press Start drive. You can change speed while driving.",
    "Press Stop (or use the notification) to end spoofing.",
    "If an app still shows your real location, close and reopen that app after pressing Start."
)

private fun openCoffeePage(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE_URL)))
    }
}

private fun openDeveloperSettings(context: Context) {
    val attempts = listOf(
        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        Settings.ACTION_DEVICE_INFO_SETTINGS,
        Settings.ACTION_SETTINGS
    )
    for (action in attempts) {
        val ok = runCatching { context.startActivity(Intent(action)) }.isSuccess
        if (ok) return
    }
}