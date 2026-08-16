@file:Suppress("DEPRECATION")

package dev.localstream.sender

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.localstream.sender.pairing.AndroidPairingStore
import dev.localstream.sender.pairing.PairingActivity
import dev.localstream.sender.pairing.PairingRecord
import dev.localstream.sender.service.StreamingService
import dev.localstream.sender.session.PublicStreamState
import dev.localstream.sender.session.StreamingSessionRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = AndroidPairingStore.create(context) { System.currentTimeMillis() / 1_000L }

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        "android.permission.ACCESS_LOCAL_NETWORK",
    )

    @Before
    fun clearPairing() {
        store.remove()
    }

    @After
    fun clearPairingAfter() {
        store.remove()
    }

    @Test
    fun unpairedScreenShowsRequiredControlsAndAllQualityProfiles() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val pairedStatus = activity.findViewById<TextView>(R.id.pairing_status)
                val pair = activity.findViewById<Button>(R.id.pair_button)
                val remove = activity.findViewById<Button>(R.id.remove_button)
                val quality = activity.findViewById<Spinner>(R.id.quality_spinner)
                val start = activity.findViewById<Button>(R.id.start_stop_button)
                val microphone = activity.findViewById<CheckBox>(R.id.microphone_toggle)

                assertEquals(activity.getString(R.string.no_pc_paired), pairedStatus.text)
                assertEquals(activity.getString(R.string.pair_pc), pair.text)
                assertEquals(View.GONE, remove.visibility)
                assertEquals(7, quality.count)
                assertEquals("Auto", quality.selectedItem.toString())
                assertFalse(start.isEnabled)
                assertTrue(start.isShown)
                assertTrue("microphone audio should be on by default", microphone.isChecked)
            }
        }
    }

    @Test
    fun successfulQrResultStartsWithoutSecondTapOrFailsClosedWhenNoCameraExists() {
        val startObserved = CountDownLatch(1)
        val listener: (dev.localstream.sender.session.PublicStreamSnapshot) -> Unit = { snapshot ->
            if (snapshot.state in setOf(
                    PublicStreamState.STARTING,
                    PublicStreamState.CONNECTING,
                    PublicStreamState.STREAMING,
                    PublicStreamState.RECONNECTING,
                )
            ) {
                startObserved.countDown()
            }
        }
        StreamingSessionRegistry.addListener(listener)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pairingMonitor = instrumentation.addMonitor(
            PairingActivity::class.java.name,
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
            true,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitForCameraProbe(scenario)
                val now = System.currentTimeMillis() / 1_000L
                val record = PairingRecord(
                    receiverId = "11111111-2222-4333-8444-555555555555",
                    label = "Test PC",
                    host = "10.0.2.2",
                    port = 9_000,
                    secret = ByteArray(32) { 7 },
                    credentialExpiresAtEpochSeconds = now + 86_400,
                    latencyMs = 120,
                    pbKeyLength = 32,
                )
                store.save(record)
                record.destroy()

                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.pair_button).performClick()
                }

                instrumentation.waitForIdleSync()
                assertTrue("the pairing activity result should come from the intercepted QR flow", pairingMonitor.hits > 0)
                val started = startObserved.await(3, TimeUnit.SECONDS)
                if (!started) {
                    scenario.onActivity { activity ->
                        assertEquals("Test PC", activity.findViewById<TextView>(R.id.pairing_status).text)
                        assertFalse(
                            "only a phone without a supported camera/encoder may decline auto-start",
                            activity.findViewById<Button>(R.id.start_stop_button).isEnabled,
                        )
                    }
                }
            }
        } finally {
            instrumentation.removeMonitor(pairingMonitor)
            StreamingSessionRegistry.removeListener(listener)
            context.startService(StreamingService.stopIntent(context))
        }
    }

    private fun waitForCameraProbe(scenario: ActivityScenario<MainActivity>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.stream_status).text
                ready = status != activity.getString(R.string.checking_camera)
            }
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("camera capability probe did not finish")
    }
}
