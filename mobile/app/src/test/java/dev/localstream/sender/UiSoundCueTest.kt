package dev.localstream.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundCueTest {
    @Test
    fun `every user action cue is short and explicitly mapped`() {
        assertEquals(setOf("PAIRED", "START", "STOP", "ERROR"), UiSoundCue.entries.map { it.name }.toSet())
        UiSoundCue.entries.forEach { cue ->
            assertTrue("${cue.name} must remain a short cue", cue.durationMilliseconds in 50..250)
            assertTrue("${cue.name} must have a valid Android tone", cue.tone >= 0)
        }
    }
}
