package dev.localstream.sender.quality

enum class VideoCodec(val mimeType: String) {
    HEVC("video/hevc"),
    AVC("video/avc"),
}

enum class QualityProfile(
    val displayName: String,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val hevcBitrateMin: Int,
    val hevcBitrateTarget: Int,
    val hevcBitrateMax: Int,
    val avcBitrateMin: Int,
    val avcBitrateTarget: Int,
    val avcBitrateMax: Int,
) {
    AUTO("Auto", 0, 0, 0, 0, 0, 0, 0, 0, 0),
    UHD_60("4K 60 FPS", 3840, 2160, 60, 35_000_000, 45_000_000, 55_000_000, 55_000_000, 65_000_000, 80_000_000),
    UHD_30("4K 30 FPS", 3840, 2160, 30, 20_000_000, 28_000_000, 35_000_000, 32_000_000, 40_000_000, 50_000_000),
    QHD_60("1440p 60 FPS", 2560, 1440, 60, 18_000_000, 24_000_000, 30_000_000, 30_000_000, 36_000_000, 45_000_000),
    FHD_60("1080p 60 FPS", 1920, 1080, 60, 10_000_000, 14_000_000, 18_000_000, 16_000_000, 20_000_000, 25_000_000),
    FHD_30("1080p 30 FPS", 1920, 1080, 30, 6_000_000, 8_000_000, 12_000_000, 8_000_000, 12_000_000, 16_000_000),
    HD_30("720p 30 FPS", 1280, 720, 30, 3_000_000, 4_500_000, 6_000_000, 4_000_000, 6_000_000, 8_000_000),
    ;

    fun targetBitrate(codec: VideoCodec): Int = when (codec) {
        VideoCodec.HEVC -> hevcBitrateTarget
        VideoCodec.AVC -> avcBitrateTarget
    }
}

data class ProfileCapability(
    val profile: QualityProfile,
    val codec: VideoCodec?,
    val available: Boolean,
    val unavailableReason: String?,
    val constrainedHighSpeed: Boolean = false,
)

sealed interface QualitySelection {
    data class Available(
        val requested: QualityProfile,
        val selected: QualityProfile,
        val codec: VideoCodec,
    ) : QualitySelection

    data class Unavailable(val profile: QualityProfile, val reason: String) : QualitySelection
}

object QualitySelector {
    private val preferenceOrder = listOf(
        QualityProfile.UHD_60,
        QualityProfile.UHD_30,
        QualityProfile.QHD_60,
        QualityProfile.FHD_60,
        QualityProfile.FHD_30,
        QualityProfile.HD_30,
    )

    fun select(
        requested: QualityProfile,
        capabilities: Map<QualityProfile, ProfileCapability>,
    ): QualitySelection {
        if (requested == QualityProfile.AUTO) {
            for (candidate in preferenceOrder) {
                val capability = capabilities[candidate]
                if (capability?.available == true && capability.codec != null) {
                    return QualitySelection.Available(requested, candidate, capability.codec)
                }
            }
            return QualitySelection.Unavailable(
                QualityProfile.AUTO,
                "This phone does not have a supported camera and hardware encoder profile.",
            )
        }

        val capability = capabilities[requested]
        if (capability?.available == true && capability.codec != null) {
            return QualitySelection.Available(requested, requested, capability.codec)
        }
        return QualitySelection.Unavailable(
            requested,
            capability?.unavailableReason ?: "This phone does not support ${requested.displayName}.",
        )
    }

    fun nextLower(
        current: QualityProfile,
        capabilities: Map<QualityProfile, ProfileCapability>,
    ): QualitySelection? {
        val currentIndex = preferenceOrder.indexOf(current)
        if (currentIndex < 0) return null
        for (index in currentIndex + 1 until preferenceOrder.size) {
            val candidate = preferenceOrder[index]
            val capability = capabilities[candidate]
            if (capability?.available == true && capability.codec != null) {
                return QualitySelection.Available(current, candidate, capability.codec)
            }
        }
        return null
    }

    fun allProductProfiles(): List<QualityProfile> = QualityProfile.entries
}

/** Picks a Camera2 AE / high-speed FPS range without aborting when the exact target is missing. */
object FpsRangeSelector {
    fun choose(candidates: List<Pair<Int, Int>>, target: Int): Pair<Int, Int>? {
        if (candidates.isEmpty() || target <= 0) return null
        val covering = candidates.filter { it.first <= target && it.second >= target }
        if (covering.isNotEmpty()) {
            return covering.minWith(
                compareBy<Pair<Int, Int>> { it.second - it.first }
                    .thenByDescending { it.first },
            )
        }
        return candidates.maxWith(
            compareBy<Pair<Int, Int>> { it.second }
                .thenBy { it.second - it.first }
                .thenByDescending { it.first },
        )
    }
}

