@file:Suppress("DEPRECATION")
@file:android.annotation.SuppressLint("SyntheticAccessor")

package dev.localstream.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.localstream.sender.pairing.AndroidPairingStore
import dev.localstream.sender.pairing.PairingActivity
import dev.localstream.sender.quality.CameraCapabilities
import dev.localstream.sender.quality.CameraCapabilityProbe
import dev.localstream.sender.quality.CameraChoice
import dev.localstream.sender.quality.QualityProfile
import dev.localstream.sender.quality.QualitySelection
import dev.localstream.sender.quality.QualitySelector
import dev.localstream.sender.service.StreamingService
import dev.localstream.sender.session.PublicStreamSnapshot
import dev.localstream.sender.session.PublicStreamState
import dev.localstream.sender.session.StreamingSessionRegistry
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var pairingStatus: TextView
    private lateinit var pairButton: Button
    private lateinit var removeButton: Button
    private lateinit var qualitySpinner: Spinner
    private lateinit var cameraLabel: TextView
    private lateinit var cameraSpinner: Spinner
    private lateinit var microphone: CheckBox
    private lateinit var startStop: Button
    private lateinit var streamStatus: TextView
    private lateinit var soundCues: LocalSoundCuePlayer
    private val probeExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "capability-probe") }
    private var capabilities: CameraCapabilities? = null
    private var cameras: List<CameraChoice> = emptyList()
    private var selectedQuality = QualityProfile.AUTO
    private var lastAcceptedQualityIndex = 0
    private var pendingStartAfterPermission = false
    private var pendingAutoStartAfterPairing = false
    private var settingQualitySelection = false
    private var lastSoundState: PublicStreamState? = null
    private val streamListener: (PublicStreamSnapshot) -> Unit = { snapshot ->
        runOnUiThread { renderStreamState(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        soundCues = LocalSoundCuePlayer()
        selectedQuality = savedInstanceState?.getString(STATE_PROFILE)?.let { saved ->
            QualityProfile.entries.firstOrNull { it.name == saved }
        } ?: QualityProfile.AUTO
        pendingStartAfterPermission = savedInstanceState?.getBoolean(STATE_PENDING_PERMISSION) == true
        pendingAutoStartAfterPairing = savedInstanceState?.getBoolean(STATE_PENDING_AUTO_START) == true
        buildUi()
        configureQualitySpinner()
        StreamingSessionRegistry.addListener(streamListener)
        probeCapabilities()
    }

    override fun onResume() {
        super.onResume()
        refreshPairingStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROFILE, selectedQuality.name)
        outState.putBoolean(STATE_PENDING_PERMISSION, pendingStartAfterPermission)
        outState.putBoolean(STATE_PENDING_AUTO_START, pendingAutoStartAfterPairing)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        StreamingSessionRegistry.removeListener(streamListener)
        probeExecutor.shutdownNow()
        soundCues.close()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PAIR_REQUEST && resultCode == RESULT_OK) {
            soundCues.play(UiSoundCue.PAIRED)
            refreshPairingStatus()
            pendingAutoStartAfterPairing = true
            streamStatus.text = getString(R.string.paired_getting_ready)
            continueAutoStartAfterPairing()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STREAM_PERMISSION_REQUEST || !pendingStartAfterPermission) return
        pendingStartAfterPermission = false
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startStreamingIfReady()
        } else {
            streamStatus.text = getString(R.string.allow_requested_access)
            soundCues.play(UiSoundCue.ERROR)
        }
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 16f
            setPadding(0, dp(6), 0, dp(24))
        }, matchWrap())

        pairingStatus = TextView(this).apply {
            id = R.id.pairing_status
            textSize = 17f
        }
        content.addView(sectionLabel("Paired PC"), matchWrap())
        content.addView(pairingStatus, matchWrap())
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(20))
        }
        pairButton = Button(this).apply {
            id = R.id.pair_button
            text = getString(R.string.pair_pc)
            setOnClickListener {
                startActivityForResult(Intent(this@MainActivity, PairingActivity::class.java), PAIR_REQUEST)
            }
        }
        removeButton = Button(this).apply {
            id = R.id.remove_button
            text = getString(R.string.remove_pc)
            setOnClickListener { confirmRemovePairing() }
        }
        pairRow.addView(pairButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pairRow.addView(removeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(pairRow, matchWrap())

        content.addView(sectionLabel(getString(R.string.quality)), matchWrap())
        qualitySpinner = Spinner(this)
        qualitySpinner.id = R.id.quality_spinner
        content.addView(qualitySpinner, matchWrap())

        cameraLabel = sectionLabel(getString(R.string.camera)).apply { setPadding(0, dp(20), 0, 0) }
        content.addView(cameraLabel, matchWrap())
        cameraSpinner = Spinner(this)
        cameraSpinner.id = R.id.camera_spinner
        content.addView(cameraSpinner, matchWrap())

        microphone = CheckBox(this).apply {
            id = R.id.microphone_toggle
            text = getString(R.string.include_microphone)
            // Camera plus microphone is the complete out-of-box sender. Users can
            // turn audio off before starting if they want a video-only source.
            isChecked = true
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(microphone, matchWrap())

        startStop = Button(this).apply {
            id = R.id.start_stop_button
            text = getString(R.string.start_streaming)
            isEnabled = false
            setOnClickListener {
                if (StreamingSessionRegistry.current().state in ACTIVE_STATES) {
                    startService(StreamingService.stopIntent(this@MainActivity))
                    soundCues.play(UiSoundCue.STOP)
                } else {
                    requestPermissionsAndStart()
                }
            }
        }
        content.addView(startStop, matchWrap())
        streamStatus = TextView(this).apply {
            id = R.id.stream_status
            text = getString(R.string.ready)
            textSize = 17f
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(streamStatus, matchWrap())
        content.addView(Button(this).apply {
            text = getString(R.string.battery_help)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.battery_help)
                    .setMessage(R.string.battery_help_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, matchWrap())

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun configureQualitySpinner() {
        val labels = QualityProfile.entries.map { it.displayName }
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val initialIndex = QualityProfile.entries.indexOf(selectedQuality).coerceAtLeast(0)
        lastAcceptedQualityIndex = initialIndex
        qualitySpinner.setSelection(initialIndex)
        qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (settingQualitySelection) return
                val profile = QualityProfile.entries[position]
                val camera = cameras.getOrNull(cameraSpinner.selectedItemPosition)
                val selection = camera?.let { QualitySelector.select(profile, it.profiles) }
                if (selection is QualitySelection.Unavailable) {
                    if (profile != QualityProfile.AUTO) {
                        Toast.makeText(this@MainActivity, selection.reason, Toast.LENGTH_LONG).show()
                    }
                    settingQualitySelection = true
                    qualitySpinner.setSelection(lastAcceptedQualityIndex)
                    settingQualitySelection = false
                    return
                }
                selectedQuality = profile
                lastAcceptedQualityIndex = position
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun probeCapabilities() {
        streamStatus.text = getString(R.string.checking_camera)
        probeExecutor.execute {
            val result = try {
                CameraCapabilityProbe(this).probe()
            } catch (_: RuntimeException) {
                CameraCapabilities(emptyList())
            }
            runOnUiThread { applyCapabilities(result) }
        }
    }

    private fun applyCapabilities(result: CameraCapabilities) {
        capabilities = result
        cameras = result.cameras
        val showCameraChoice = cameras.size > 1
        cameraLabel.visibility = if (showCameraChoice) View.VISIBLE else View.GONE
        cameraSpinner.visibility = if (showCameraChoice) View.VISIBLE else View.GONE
        cameraSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameras.mapIndexed { index, camera ->
                if (cameras.size > 1) getString(R.string.camera_indexed, camera.label, index + 1) else camera.label
            },
        )
        cameraSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateQualityLabels(cameras.getOrNull(position))
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateQualityLabels(cameras.firstOrNull())
        streamStatus.text = if (cameras.isEmpty()) {
            getString(R.string.no_camera_found)
        } else {
            StreamingSessionRegistry.current().message
        }
        updateStartEnabled()
        continueAutoStartAfterPairing()
    }

    private fun continueAutoStartAfterPairing() {
        val currentState = StreamingSessionRegistry.current().state
        val selectedCamera = cameras.getOrNull(cameraSpinner.selectedItemPosition)
        val selection = selectedCamera?.let { camera -> QualitySelector.select(selectedQuality, camera.profiles) }
        when (
            pairingAutoStartAction(
                pendingAfterSuccessfulScan = pendingAutoStartAfterPairing,
                capabilitiesReady = capabilities != null,
                streamConfigurationAvailable = selection is QualitySelection.Available,
                streamActive = currentState in ACTIVE_STATES,
            )
        ) {
            PairingAutoStartAction.NONE -> {
                if (currentState in ACTIVE_STATES) pendingAutoStartAfterPairing = false
            }

            PairingAutoStartAction.WAIT_FOR_CAPABILITIES -> {
                streamStatus.text = getString(R.string.paired_getting_ready)
            }

            PairingAutoStartAction.SHOW_UNAVAILABLE_CONFIGURATION -> {
                pendingAutoStartAfterPairing = false
                streamStatus.text = (selection as? QualitySelection.Unavailable)?.reason
                    ?: getString(R.string.no_camera_found)
                soundCues.play(UiSoundCue.ERROR)
            }

            PairingAutoStartAction.REQUEST_PERMISSIONS -> {
                pendingAutoStartAfterPairing = false
                streamStatus.text = getString(R.string.paired_starting_stream)
                requestPermissionsAndStart()
            }
        }
    }

    private fun updateQualityLabels(camera: CameraChoice?) {
        if (selectedQuality != QualityProfile.AUTO &&
            camera?.profiles?.get(selectedQuality)?.available != true
        ) {
            selectedQuality = QualityProfile.AUTO
            lastAcceptedQualityIndex = QualityProfile.entries.indexOf(QualityProfile.AUTO)
        }
        val labels = QualityProfile.entries.map { profile ->
            val capability = camera?.profiles?.get(profile)
            when {
                profile == QualityProfile.AUTO -> profile.displayName
                capability?.available == true -> getString(
                    R.string.quality_with_codec,
                    profile.displayName,
                    capability.codec?.name.orEmpty(),
                )
                else -> getString(R.string.quality_unavailable, profile.displayName)
            }
        }
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val currentIndex = QualityProfile.entries.indexOf(selectedQuality).coerceAtLeast(0)
        settingQualitySelection = true
        qualitySpinner.setSelection(currentIndex)
        settingQualitySelection = false
    }

    private fun requestPermissionsAndStart() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) add(POST_NOTIFICATIONS_PERMISSION)
            if (microphone.isChecked) add(Manifest.permission.RECORD_AUDIO)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) {
            startStreamingIfReady()
        } else {
            pendingStartAfterPermission = true
            requestPermissions(permissions.toTypedArray(), STREAM_PERMISSION_REQUEST)
        }
    }

    private fun startStreamingIfReady() {
        val pairing = AndroidPairingStore.create(this) { System.currentTimeMillis() / 1_000L }.load()
        if (pairing == null) {
            streamStatus.text = getString(R.string.pair_to_start)
            return
        }
        pairing.destroy()
        val camera = cameras.getOrNull(cameraSpinner.selectedItemPosition)
        if (camera == null) {
            streamStatus.text = getString(R.string.no_camera_found)
            return
        }
        val selection = QualitySelector.select(selectedQuality, camera.profiles)
        if (selection is QualitySelection.Unavailable) {
            streamStatus.text = selection.reason
            return
        }
        startForegroundService(
            StreamingService.startIntent(
                this,
                selectedQuality,
                camera.cameraId,
                microphone.isChecked,
            ),
        )
        soundCues.play(UiSoundCue.START)
    }

    private fun refreshPairingStatus() {
        val pairing = AndroidPairingStore.create(this) { System.currentTimeMillis() / 1_000L }.load()
        if (pairing == null) {
            pairingStatus.text = getString(R.string.no_pc_paired)
            pairButton.text = getString(R.string.pair_pc)
            removeButton.visibility = View.GONE
        } else {
            pairingStatus.text = pairing.label
            pairButton.text = getString(R.string.change_pc)
            removeButton.visibility = View.VISIBLE
            pairing.destroy()
        }
        updateStartEnabled()
    }

    private fun confirmRemovePairing() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_pairing_title)
            .setMessage(R.string.remove_pairing_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_pc) { _, _ ->
                startService(StreamingService.stopIntent(this))
                AndroidPairingStore.create(this) { System.currentTimeMillis() / 1_000L }.remove()
                refreshPairingStatus()
            }
            .show()
    }

    private fun renderStreamState(snapshot: PublicStreamSnapshot) {
        streamStatus.text = if (snapshot.profileName == null) {
            snapshot.message
        } else {
            getString(R.string.quality_with_codec, snapshot.message, snapshot.profileName)
        }
        val active = snapshot.state in ACTIVE_STATES
        startStop.text = if (active) getString(R.string.stop_streaming) else getString(R.string.start_streaming)
        pairButton.isEnabled = !active
        removeButton.isEnabled = !active
        qualitySpinner.isEnabled = !active
        cameraSpinner.isEnabled = !active
        microphone.isEnabled = !active
        if (snapshot.state == PublicStreamState.ERROR && lastSoundState != PublicStreamState.ERROR) {
            soundCues.play(UiSoundCue.ERROR)
        }
        lastSoundState = snapshot.state
        updateStartEnabled()
    }

    private fun updateStartEnabled() {
        val state = StreamingSessionRegistry.current().state
        val camera = cameras.getOrNull(cameraSpinner.selectedItemPosition)
        val qualityAvailable = camera != null &&
            QualitySelector.select(selectedQuality, camera.profiles) is QualitySelection.Available
        startStop.isEnabled = state in ACTIVE_STATES || (qualityAvailable && hasStoredPairing())
    }

    private fun hasStoredPairing(): Boolean {
        val pairing = AndroidPairingStore.create(this) { System.currentTimeMillis() / 1_000L }.load() ?: return false
        pairing.destroy()
        return true
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAIR_REQUEST = 80
        private const val STREAM_PERMISSION_REQUEST = 81
        private const val STATE_PROFILE = "selected_profile"
        private const val STATE_PENDING_PERMISSION = "pending_permission"
        private const val STATE_PENDING_AUTO_START = "pending_auto_start"
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        private val ACTIVE_STATES = setOf(
            PublicStreamState.STARTING,
            PublicStreamState.CONNECTING,
            PublicStreamState.STREAMING,
            PublicStreamState.RECONNECTING,
            PublicStreamState.STOPPING,
        )
    }
}
