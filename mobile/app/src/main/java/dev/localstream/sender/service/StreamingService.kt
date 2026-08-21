@file:Suppress("DEPRECATION")

package dev.localstream.sender.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import dev.localstream.sender.MainActivity
import dev.localstream.sender.R
import dev.localstream.sender.media.MediaFailure
import dev.localstream.sender.media.MediaPipeline
import dev.localstream.sender.media.MediaPipelineConfig
import dev.localstream.sender.media.VideoEncoderConfig
import dev.localstream.sender.pairing.AndroidPairingStore
import dev.localstream.sender.quality.CameraCapabilities
import dev.localstream.sender.quality.CameraCapabilityProbe
import dev.localstream.sender.quality.CameraChoice
import dev.localstream.sender.quality.QualityProfile
import dev.localstream.sender.quality.QualitySelection
import dev.localstream.sender.quality.QualitySelector
import dev.localstream.sender.session.PublicStreamSnapshot
import dev.localstream.sender.session.PublicStreamState
import dev.localstream.sender.session.StreamEvent
import dev.localstream.sender.session.StreamStateMachine
import dev.localstream.sender.session.StreamingSessionRegistry
import dev.localstream.sender.transport.TransportError
import dev.localstream.sender.transport.TransportStatus
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** User-started foreground owner for all long-lived camera, microphone, network, and power resources. */
class StreamingService : Service() {
    private val stopping = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private val stateMachine = StreamStateMachine(initiallyPaired = false, initiallyPermitted = false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "stream-session") }
    private var monitor: ScheduledExecutorService? = null
    private var pipeline: MediaPipeline? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var selectedCamera: CameraChoice? = null
    private var capabilities: CameraCapabilities? = null
    private var selectedProfile: QualityProfile? = null
    private var microphoneEnabled = false
    private var highQueueSamples = 0
    private var thermalDowngradeInProgress = false
    private var wakeLockRenewAtElapsedMilliseconds = 0L
    private var credentialExpiresAtEpochSeconds = 0L
    private var lastNotificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @android.annotation.SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop("Streaming stopped")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || stopping.get() || !startRequested.compareAndSet(false, true)) {
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (intent.getBooleanExtra(EXTRA_MICROPHONE, false)) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    },
            )
        } catch (_: RuntimeException) {
            StreamingSessionRegistry.publish(
                PublicStreamSnapshot(PublicStreamState.ERROR, "Streaming could not start in the background."),
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        microphoneEnabled = intent.getBooleanExtra(EXTRA_MICROPHONE, false)
        val requestedProfileName = intent.getStringExtra(EXTRA_PROFILE)
        val requestedCameraId = intent.getStringExtra(EXTRA_CAMERA_ID)
        worker.execute { startSession(requestedProfileName, requestedCameraId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // A task swipe is not an explicit stop. The persistent notification remains the user control.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cleanupSession("Streaming stopped", PublicStreamState.IDLE)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun startSession(requestedProfileName: String?, requestedCameraId: String?) {
        val permissionsGranted = hasRequiredPermissions()
        stateMachine.handle(StreamEvent.PermissionsUpdated(permissionsGranted))
        if (!permissionsGranted) {
            cleanupAndStop("Required permission is missing.", PublicStreamState.ERROR)
            return
        }
        val pairing = AndroidPairingStore.create(this) { nowEpochSeconds() }.load()
        if (pairing == null) {
            cleanupAndStop("Pair a PC before starting.", PublicStreamState.ERROR)
            return
        }
        stateMachine.handle(StreamEvent.PairingAvailable)
        try {
            capabilities = CameraCapabilityProbe(this).probe()
        } catch (_: RuntimeException) {
            pairing.destroy()
            cleanupAndStop("Camera capabilities could not be read.", PublicStreamState.ERROR)
            return
        }
        val camera = capabilities?.cameras?.firstOrNull { it.cameraId == requestedCameraId }
            ?: capabilities?.defaultCamera
        if (camera == null) {
            pairing.destroy()
            cleanupAndStop("No supported camera was found.", PublicStreamState.ERROR)
            return
        }
        val requested = requestedProfileName?.let { name ->
            QualityProfile.entries.firstOrNull { it.name == name }
        } ?: QualityProfile.AUTO
        val selection = QualitySelector.select(requested, camera.profiles)
        if (selection is QualitySelection.Unavailable) {
            pairing.destroy()
            cleanupAndStop(selection.reason, PublicStreamState.ERROR)
            return
        }
        if (selection !is QualitySelection.Available) {
            pairing.destroy()
            cleanupAndStop("The selected quality is not available.", PublicStreamState.ERROR)
            return
        }
        selectedCamera = camera
        selectedProfile = selection.selected
        credentialExpiresAtEpochSeconds = pairing.credentialExpiresAtEpochSeconds
        stateMachine.handle(StreamEvent.StartRequested)
        if (!startPipeline(pairing, camera, selection)) return
        try {
            acquireSessionResources()
            registerNetworkCallback()
            registerThermalListener()
            startMonitor()
        } catch (_: RuntimeException) {
            cleanupAndStop("Streaming resources could not be started.", PublicStreamState.ERROR)
        }
    }

    private fun startPipeline(
        pairing: dev.localstream.sender.pairing.PairingRecord,
        camera: CameraChoice,
        selection: QualitySelection.Available,
    ): Boolean {
        val capability = camera.profiles[selection.selected]
        if (capability == null || !capability.available) {
            pairing.destroy()
            cleanupAndStop("The selected quality is no longer available.", PublicStreamState.ERROR)
            return false
        }
        StreamingSessionRegistry.publish(
            PublicStreamSnapshot(PublicStreamState.STARTING, "Starting camera…", selection.selected.displayName),
        )
        val generation = stateMachine.snapshot().generation
        val created = try {
            MediaPipeline(
                context = this,
                config = MediaPipelineConfig(
                    pairing = pairing,
                    video = VideoEncoderConfig(camera.cameraId, selection.selected, capability, selection.codec),
                    microphoneEnabled = microphoneEnabled,
                ),
                onFailure = { failure ->
                    worker.execute {
                        if (generation == stateMachine.snapshot().generation) handleMediaFailure(failure)
                    }
                },
            )
        } catch (_: LinkageError) {
            pairing.destroy()
            cleanupAndStop("The secure transport is not available on this phone.", PublicStreamState.ERROR)
            return false
        } catch (_: RuntimeException) {
            pairing.destroy()
            cleanupAndStop("The media pipeline could not be created.", PublicStreamState.ERROR)
            return false
        }
        pipeline = created
        val started = try {
            created.start()
        } catch (_: RuntimeException) {
            false
        }
        if (!started) {
            cleanupAndStop("Camera or encoder could not start.", PublicStreamState.ERROR)
            return false
        }
        stateMachine.handle(StreamEvent.PipelineReady(generation))
        StreamingSessionRegistry.publish(
            PublicStreamSnapshot(PublicStreamState.CONNECTING, "Connecting…", selection.selected.displayName),
        )
        updateNotification("Connecting… · ${selection.selected.displayName}")
        return true
    }

    private fun startMonitor() {
        monitor?.shutdownNow()
        monitor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "stream-monitor") }
            .also { scheduler ->
                scheduler.scheduleWithFixedDelay(
                    { worker.execute(::pollSession) },
                    0,
                    MONITOR_INTERVAL_MILLISECONDS,
                    TimeUnit.MILLISECONDS,
                )
            }
    }

    private fun pollSession() {
        if (stopping.get()) return
        if (credentialExpiresAtEpochSeconds <= nowEpochSeconds()) {
            cleanupAndStop("This pairing has expired. Pair the PC again.", PublicStreamState.ERROR)
            return
        }
        if (!hasRequiredPermissions()) {
            cleanupAndStop("A required permission was removed. Streaming stopped safely.", PublicStreamState.ERROR)
            return
        }
        val activePipeline = pipeline ?: return
        renewWakeLockIfNeeded()
        val profile = selectedProfile?.displayName
        val transportStatus = activePipeline.transportStatus()
        when (transportStatus) {
            TransportStatus.STOPPED,
            TransportStatus.CONNECTING,
            -> {
                stateMachine.handle(StreamEvent.RetryConnecting(stateMachine.snapshot().generation))
                StreamingSessionRegistry.publish(
                    PublicStreamSnapshot(PublicStreamState.CONNECTING, "Connecting…", profile),
                )
                updateNotification(profile?.let { "Connecting… · $it" } ?: "Connecting…")
            }

            TransportStatus.NEEDS_KEY_FRAME -> {
                // Native transport has completed SRT setup and intentionally discarded
                // all pre-connect media. Ask the encoder for a fresh IDR until one arrives.
                activePipeline.requestKeyFrame(force = true)
                stateMachine.handle(StreamEvent.RetryConnecting(stateMachine.snapshot().generation))
                StreamingSessionRegistry.publish(
                    PublicStreamSnapshot(PublicStreamState.CONNECTING, "Starting video…", profile),
                )
                updateNotification(profile?.let { "Starting video… · $it" } ?: "Starting video…")
            }

            TransportStatus.CONNECTED -> {
                stateMachine.handle(StreamEvent.TransportConnected(stateMachine.snapshot().generation))
                StreamingSessionRegistry.publish(
                    PublicStreamSnapshot(PublicStreamState.STREAMING, "Streaming", profile),
                )
                updateNotification(profile?.let { "Streaming · $it" } ?: "Streaming")
            }

            TransportStatus.RECONNECTING -> {
                stateMachine.handle(StreamEvent.NetworkLost(stateMachine.snapshot().generation))
                StreamingSessionRegistry.publish(
                    PublicStreamSnapshot(PublicStreamState.RECONNECTING, "Reconnecting…", profile),
                )
                updateNotification(profile?.let { "Reconnecting… · $it" } ?: "Reconnecting…")
            }

            TransportStatus.AUTHENTICATION_FAILED -> {
                cleanupAndStop("The PC rejected this pairing. Pair it again.", PublicStreamState.ERROR)
                return
            }

            TransportStatus.FAILED -> {
                val message = when (activePipeline.transportError()) {
                    TransportError.RECONNECT_EXHAUSTED -> "The PC could not be reached after several tries."
                    TransportError.ENCRYPTION_REJECTED -> "The encrypted connection was rejected."
                    else -> "Streaming stopped after a connection error."
                }
                cleanupAndStop(message, PublicStreamState.ERROR)
                return
            }
        }
        if (transportStatus == TransportStatus.CONNECTED) {
            highQueueSamples = if (activePipeline.queuePercent() >= HIGH_QUEUE_PERCENT) highQueueSamples + 1 else 0
            if (highQueueSamples >= HIGH_QUEUE_SAMPLE_LIMIT) {
                highQueueSamples = 0
                restartAtLowerQuality("Network is congested. Lowering quality…")
            }
        } else {
            // A queue that grows before connection/reconnection is not evidence of
            // throughput congestion and must not trigger a quality downgrade.
            highQueueSamples = 0
        }
    }

    private fun restartAtLowerQuality(message: String) {
        if (thermalDowngradeInProgress || stopping.get()) return
        val camera = selectedCamera ?: return
        val current = selectedProfile ?: return
        val lower = QualitySelector.nextLower(current, camera.profiles) as? QualitySelection.Available ?: return
        thermalDowngradeInProgress = true
        StreamingSessionRegistry.publish(PublicStreamSnapshot(PublicStreamState.RECONNECTING, message, lower.selected.displayName))
        val stoppingSnapshot = stateMachine.handle(StreamEvent.StopRequested)
        pipeline?.close()
        pipeline = null
        stateMachine.handle(StreamEvent.CleanupComplete(stoppingSnapshot.generation))
        stateMachine.handle(StreamEvent.StartRequested)
        val pairing = AndroidPairingStore.create(this) { nowEpochSeconds() }.load()
        if (pairing == null || !startPipeline(pairing, camera, lower)) {
            pairing?.destroy()
            cleanupAndStop("Quality could not be lowered safely.", PublicStreamState.ERROR)
            return
        }
        selectedProfile = lower.selected
        thermalDowngradeInProgress = false
    }

    private fun handleMediaFailure(failure: MediaFailure) {
        if (stopping.get()) return
        val generation = stateMachine.snapshot().generation
        if (failure == MediaFailure.AUDIO_FOCUS_LOST) {
            stateMachine.handle(StreamEvent.Interrupted(generation))
        } else {
            stateMachine.handle(StreamEvent.TerminalFailure(generation))
        }
        val message = when (failure) {
            MediaFailure.AUDIO_FOCUS_LOST -> "Another app needs the microphone. Streaming stopped."
            MediaFailure.CAMERA_PERMISSION,
            MediaFailure.AUDIO_PERMISSION,
            -> "A required permission was removed. Streaming stopped safely."

            MediaFailure.CAMERA_UNAVAILABLE,
            MediaFailure.CAMERA_SESSION,
            -> "The camera became unavailable. Streaming stopped safely."

            MediaFailure.VIDEO_ENCODER -> "The video encoder stopped. Streaming ended safely."
            MediaFailure.AUDIO_CAPTURE,
            MediaFailure.AUDIO_ENCODER,
            -> "The microphone stopped. Streaming ended safely."
        }
        cleanupAndStop(message, PublicStreamState.ERROR)
    }

    private fun acquireSessionResources() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:stream").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLISECONDS)
        }
        wakeLockRenewAtElapsedMilliseconds = android.os.SystemClock.elapsedRealtime() + WAKE_LOCK_RENEW_MILLISECONDS
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                stateMachine.handle(StreamEvent.NetworkLost(stateMachine.snapshot().generation))
                StreamingSessionRegistry.publish(
                    PublicStreamSnapshot(PublicStreamState.RECONNECTING, "Wi-Fi disconnected. Trying again…", selectedProfile?.displayName),
                )
                updateNotification("Wi-Fi disconnected. Reconnecting…")
            }

            override fun onAvailable(network: Network) {
                stateMachine.handle(StreamEvent.RetryConnecting(stateMachine.snapshot().generation))
                pipeline?.requestKeyFrame()
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (_: RuntimeException) {
            networkCallback = null
        }
    }

    private fun registerThermalListener() {
        val powerManager = getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            worker.execute {
                when {
                    status >= PowerManager.THERMAL_STATUS_CRITICAL ->
                        cleanupAndStop("Phone is too hot. Streaming stopped safely.", PublicStreamState.ERROR)

                    status >= PowerManager.THERMAL_STATUS_SEVERE ->
                        restartAtLowerQuality("Phone is getting warm. Lowering quality…")
                }
            }
        }
        thermalListener = listener
        powerManager.addThermalStatusListener(worker, listener)
    }

    private fun hasRequiredPermissions(): Boolean {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        if (microphoneEnabled && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    private fun renewWakeLockIfNeeded() {
        val lock = wakeLock ?: return
        if (android.os.SystemClock.elapsedRealtime() < wakeLockRenewAtElapsedMilliseconds) return
        if (lock.isHeld) lock.release()
        lock.acquire(WAKE_LOCK_TIMEOUT_MILLISECONDS)
        wakeLockRenewAtElapsedMilliseconds = android.os.SystemClock.elapsedRealtime() + WAKE_LOCK_RENEW_MILLISECONDS
    }

    private fun requestStop(message: String) {
        worker.execute { cleanupAndStop(message, PublicStreamState.IDLE) }
    }

    private fun cleanupAndStop(message: String, state: PublicStreamState) {
        cleanupSession(message, state)
        stopSelf()
    }

    @Synchronized
    private fun cleanupSession(message: String, state: PublicStreamState) {
        if (!stopping.compareAndSet(false, true)) return
        val stoppingSnapshot = stateMachine.handle(StreamEvent.StopRequested)
        StreamingSessionRegistry.publish(PublicStreamSnapshot(PublicStreamState.STOPPING, "Stopping…"))
        monitor?.shutdownNow()
        monitor = null
        val connectivity = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { callback ->
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: RuntimeException) {
                // A callback can already be unregistered during system teardown.
            }
        }
        networkCallback = null
        thermalListener?.let { listener ->
            getSystemService(PowerManager::class.java).removeThermalStatusListener(listener)
        }
        thermalListener = null
        pipeline?.close()
        pipeline = null
        if (wifiLock?.isHeld == true) wifiLock?.release()
        wifiLock = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        credentialExpiresAtEpochSeconds = 0L
        startRequested.set(false)
        lastNotificationText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stateMachine.handle(StreamEvent.CleanupComplete(stoppingSnapshot.generation))
        StreamingSessionRegistry.publish(PublicStreamSnapshot(state, message))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "Camera streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the streaming stop control visible"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stream)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_streaming), stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1_000L

    companion object {
        const val ACTION_START = "dev.localstream.sender.action.START_STREAMING"
        const val ACTION_STOP = "dev.localstream.sender.action.STOP_STREAMING"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_MICROPHONE = "microphone"
        private const val NOTIFICATION_CHANNEL = "camera_streaming"
        private const val NOTIFICATION_ID = 10_021
        private const val MONITOR_INTERVAL_MILLISECONDS = 500L
        private const val HIGH_QUEUE_PERCENT = 80
        private const val HIGH_QUEUE_SAMPLE_LIMIT = 10
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val WAKE_LOCK_TIMEOUT_MILLISECONDS = 10 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_MILLISECONDS = 9 * 60 * 1_000L

        fun startIntent(
            context: Context,
            profile: QualityProfile,
            cameraId: String,
            microphoneEnabled: Boolean,
        ): Intent = Intent(context, StreamingService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_PROFILE, profile.name)
            .putExtra(EXTRA_CAMERA_ID, cameraId)
            .putExtra(EXTRA_MICROPHONE, microphoneEnabled)

        fun stopIntent(context: Context): Intent =
            Intent(context, StreamingService::class.java).setAction(ACTION_STOP)
    }
}
