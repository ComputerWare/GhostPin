package org.computerware.ghostpin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps pushing mock fixes once a second, either holding one
 * position or "driving" along a road route, so it survives the UI going away.
 */
class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MockLocationEngine
    private var loopJob: Job? = null
    private var lastTick = 0L

    // Hold-position mode
    private var target: LatLng? = null

    // Drive mode
    private var sim: RouteSim? = null
    private var travelled = 0.0
    private var speedMps = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = MockLocationEngine(this)
        val channel = NotificationChannel(CHANNEL_ID, "Mock location", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
                val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
                if (lat.isNaN() || lng.isNaN()) {
                    promote("Starting…")
                    stopSpoofing(SpoofStatus.Failed("Invalid coordinates"))
                } else {
                    begin(LatLng(lat, lng))
                }
            }
            ACTION_DRIVE -> beginDrive(intent.getIntExtra(EXTRA_SPEED, 50))
            ACTION_SPEED -> setSpeed(intent.getIntExtra(EXTRA_SPEED, 50))
            else -> stopSpoofing()
        }
        return START_NOT_STICKY
    }

    // ---- Hold a single position -------------------------------------------------------

    private fun begin(newTarget: LatLng) {
        promote(newTarget.pretty())
        if (!ensureEngine()) return

        sim = null
        travelled = 0.0
        target = newTarget
        SpoofState.set(SpoofStatus.Running(newTarget))
        ensureLoop()
    }

    // ---- Simulate a drive -------------------------------------------------------------

    private fun beginDrive(speedKmh: Int) {
        promote("Driving to destination")

        val route = SpoofState.pendingRoute
        if (route == null || route.points.size < 2) {
            stopSpoofing(SpoofStatus.Failed("No route loaded"))
            return
        }
        if (!ensureEngine()) return

        val s = RouteSim(route.points)
        sim = s
        travelled = 0.0
        target = null
        speedMps = speedKmh / 3.6
        lastTick = SystemClock.elapsedRealtime()

        SpoofState.set(SpoofStatus.Driving(route.points.first(), 0f, s.totalMeters, speedKmh))
        ensureLoop()
    }

    private fun setSpeed(speedKmh: Int) {
        if (sim != null) {
            speedMps = speedKmh / 3.6
        } else if (loopJob?.isActive != true) {
            stopSelf() // nothing running; don't linger
        }
    }

    // ---- Shared plumbing --------------------------------------------------------------

    /** Starts the mock providers if needed. On failure, shuts down and returns false. */
    private fun ensureEngine(): Boolean {
        if (engine.running) return true
        try {
            engine.start()
            return true
        } catch (e: SecurityException) {
            stopSpoofing(SpoofStatus.NotMockApp)
        } catch (e: Exception) {
            stopSpoofing(SpoofStatus.Failed(e.message ?: "Could not start mock provider"))
        }
        return false
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        lastTick = SystemClock.elapsedRealtime()
        loopJob = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastTick) / 1000.0
                lastTick = now
                try {
                    tick(dt)
                } catch (e: Exception) {
                    // e.g. the user un-selected the mock app while running
                    stopSpoofing(
                        if (e is SecurityException) SpoofStatus.NotMockApp
                        else SpoofStatus.Failed(e.message ?: "Lost mock provider")
                    )
                    break
                }
                delay(1000)
            }
        }
    }

    private fun tick(dt: Double) {
        val s = sim
        if (s == null) {
            target?.let { engine.push(it) }
            return
        }

        travelled = (travelled + speedMps * dt).coerceAtMost(s.totalMeters)
        val fix = s.at(travelled)
        val arrived = travelled >= s.totalMeters

        engine.push(fix.position, fix.bearing, if (arrived) 0f else speedMps.toFloat())

        if (arrived) {
            // Park at the destination and keep holding it until the user stops.
            sim = null
            target = fix.position
            SpoofState.set(SpoofStatus.Running(fix.position))
            promote("Arrived: ${fix.position.pretty()}")
        } else {
            SpoofState.set(
                SpoofStatus.Driving(
                    position = fix.position,
                    progress = (travelled / s.totalMeters).toFloat(),
                    remainingMeters = s.totalMeters - travelled,
                    speedKmh = (speedMps * 3.6).roundToInt()
                )
            )
        }
    }

    private fun stopSpoofing(finalStatus: SpoofStatus = SpoofStatus.Idle) {
        loopJob?.cancel()
        loopJob = null
        target = null
        sim = null
        travelled = 0.0
        SpoofState.pendingRoute = null
        engine.stop()
        SpoofState.set(finalStatus)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promote(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GhostPin is spoofing location")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        engine.stop()
        val s = SpoofState.status.value
        if (s is SpoofStatus.Running || s is SpoofStatus.Driving) SpoofState.set(SpoofStatus.Idle)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ghostpin_mock"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "org.computerware.ghostpin.START"
        private const val ACTION_DRIVE = "org.computerware.ghostpin.DRIVE"
        private const val ACTION_SPEED = "org.computerware.ghostpin.SPEED"
        private const val ACTION_STOP = "org.computerware.ghostpin.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_SPEED = "speed_kmh"

        fun start(context: Context, target: LatLng) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LAT, target.latitude)
                .putExtra(EXTRA_LNG, target.longitude)
            ContextCompat.startForegroundService(context, intent)
        }

        /** The route itself is read from [SpoofState.pendingRoute]. */
        fun startDrive(context: Context, speedKmh: Int) {
            val intent = Intent(context, MockLocationService::class.java)
                .setAction(ACTION_DRIVE)
                .putExtra(EXTRA_SPEED, speedKmh)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setSpeed(context: Context, speedKmh: Int) {
            context.startService(
                Intent(context, MockLocationService::class.java)
                    .setAction(ACTION_SPEED)
                    .putExtra(EXTRA_SPEED, speedKmh)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}