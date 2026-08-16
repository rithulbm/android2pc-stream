package dev.localstream.sender.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySelectorTest {
    @Test
    fun everyProductProfileRemainsVisibleAndAutoChoosesHighestSupported() {
        assertEquals(7, QualitySelector.allProductProfiles().size)
        val capabilities = capabilities(
            supported = setOf(QualityProfile.FHD_60, QualityProfile.HD_30),
        )

        val selection = QualitySelector.select(QualityProfile.AUTO, capabilities)
            as QualitySelection.Available

        assertEquals(QualityProfile.FHD_60, selection.selected)
        assertEquals(VideoCodec.HEVC, selection.codec)
    }

    @Test
    fun unavailableExplicitProfileReturnsPreciseReasonWithoutUpsampling() {
        val reason = "This phone does not support 4K at 60 FPS."
        val capabilities = capabilities(emptySet()).toMutableMap()
        capabilities[QualityProfile.UHD_60] = ProfileCapability(
            QualityProfile.UHD_60,
            null,
            false,
            reason,
        )

        val selection = QualitySelector.select(QualityProfile.UHD_60, capabilities)
            as QualitySelection.Unavailable

        assertEquals(reason, selection.reason)
        assertEquals(QualityProfile.UHD_60, selection.profile)
    }

    @Test
    fun thermalFallbackSelectsOnlyARealLowerCapability() {
        val capabilities = capabilities(
            supported = setOf(QualityProfile.UHD_60, QualityProfile.FHD_30),
        )
        val fallback = QualitySelector.nextLower(QualityProfile.UHD_60, capabilities)
            as QualitySelection.Available
        assertEquals(QualityProfile.FHD_30, fallback.selected)
        assertNull(QualitySelector.nextLower(QualityProfile.HD_30, capabilities))
    }

    @Test
    fun noSupportedEncoderFailsAutoSelection() {
        assertTrue(
            QualitySelector.select(QualityProfile.AUTO, capabilities(emptySet())) is
                QualitySelection.Unavailable,
        )
    }

    @Test
    fun fpsRangePrefersTightCoveringRangeThenHighestUpperBound() {
        assertEquals(30 to 30, FpsRangeSelector.choose(listOf(15 to 30, 30 to 30, 15 to 15), 30))
        assertEquals(60 to 60, FpsRangeSelector.choose(listOf(7 to 60, 60 to 60, 30 to 30), 60))
        assertEquals(7 to 60, FpsRangeSelector.choose(listOf(15 to 30, 7 to 60, 30 to 30), 60))
        assertEquals(15 to 30, FpsRangeSelector.choose(listOf(15 to 15, 15 to 30), 60))
        assertNull(FpsRangeSelector.choose(emptyList(), 60))
        assertNull(FpsRangeSelector.choose(listOf(15 to 30), 0))
    }

    private fun capabilities(
        supported: Set<QualityProfile>,
    ): Map<QualityProfile, ProfileCapability> = QualityProfile.entries
        .filter { it != QualityProfile.AUTO }
        .associateWith { profile ->
            ProfileCapability(
                profile = profile,
                codec = if (supported.contains(profile)) VideoCodec.HEVC else null,
                available = supported.contains(profile),
                unavailableReason = if (supported.contains(profile)) null else
                    "This phone does not support ${profile.displayName}.",
            )
        }
}

