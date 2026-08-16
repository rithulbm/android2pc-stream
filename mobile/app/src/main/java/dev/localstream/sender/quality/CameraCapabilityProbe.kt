package dev.localstream.sender.quality

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Size

data class CameraChoice(
    val cameraId: String,
    val label: String,
    val lensFacing: Int,
    val profiles: Map<QualityProfile, ProfileCapability>,
)

data class CameraCapabilities(val cameras: List<CameraChoice>) {
    val defaultCamera: CameraChoice? = cameras.firstOrNull()
}

/** Reads Camera2 and hardware MediaCodec limits without opening either device. */
class CameraCapabilityProbe(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)

    fun probe(): CameraCapabilities {
        val encoders = EncoderInventory.hardwareVideoEncoders()
        val allChoices = cameraManager.cameraIdList.mapNotNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@mapNotNull null
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.toSet()
                .orEmpty()
            val supportsHighSpeed =
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in capabilities
            val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { it.lower to it.upper }
                .orEmpty()
            CameraChoice(
                cameraId = cameraId,
                label = lensLabel(lensFacing),
                lensFacing = lensFacing,
                profiles = evaluateProfiles(map, supportsHighSpeed, encoders, aeRanges),
            )
        }.sortedWith(
            compareBy<CameraChoice> { it.lensFacing != CameraCharacteristics.LENS_FACING_BACK }
                .thenBy { it.cameraId },
        )
        val rearChoices = allChoices.filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
        val choices = rearChoices.ifEmpty { allChoices }
        return CameraCapabilities(choices)
    }

    private fun evaluateProfiles(
        map: StreamConfigurationMap,
        supportsHighSpeed: Boolean,
        encoders: List<EncoderSupport>,
        aeRanges: List<Pair<Int, Int>>,
    ): Map<QualityProfile, ProfileCapability> {
        val recordingSizes = map.getOutputSizes(MediaRecorder::class.java)?.toSet().orEmpty()
        val highSpeedSizes = if (supportsHighSpeed) map.highSpeedVideoSizes.toSet() else emptySet()
        return QualityProfile.entries.associateWith { profile ->
            if (profile == QualityProfile.AUTO) {
                ProfileCapability(profile, null, true, null)
            } else {
                val size = Size(profile.width, profile.height)
                val regularFps = maximumRegularFps(map, size)
                val selectedAe = FpsRangeSelector.choose(aeRanges, profile.framesPerSecond)
                val regularSupported = size in recordingSizes &&
                    regularFps >= profile.framesPerSecond &&
                    selectedAe != null &&
                    selectedAe.second >= profile.framesPerSecond
                val highSpeedSupported = size in highSpeedSizes &&
                    map.getHighSpeedVideoFpsRangesFor(size).any { range ->
                        range.lower <= profile.framesPerSecond && range.upper >= profile.framesPerSecond
                    }
                val cameraSupported = regularSupported || highSpeedSupported
                val codec = if (cameraSupported) {
                    chooseCodec(encoders, profile)
                } else {
                    null
                }
                when {
                    !cameraSupported -> ProfileCapability(
                        profile,
                        null,
                        false,
                        "This camera cannot capture ${profile.displayName}.",
                    )

                    codec == null -> ProfileCapability(
                        profile,
                        null,
                        false,
                        "This phone has no hardware encoder for ${profile.displayName}.",
                    )

                    else -> ProfileCapability(
                        profile,
                        codec,
                        true,
                        null,
                        constrainedHighSpeed = !regularSupported && highSpeedSupported,
                    )
                }
            }
        }
    }

    private fun maximumRegularFps(map: StreamConfigurationMap, size: Size): Int {
        val duration = try {
            map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        } catch (_: IllegalArgumentException) {
            0L
        }
        if (duration <= 0L) return 30
        return (1_000_000_000L / duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun chooseCodec(encoders: List<EncoderSupport>, profile: QualityProfile): VideoCodec? =
        listOf(VideoCodec.HEVC, VideoCodec.AVC).firstOrNull { codec ->
            encoders.any { it.codec == codec && it.supports(profile) }
        }

    private fun lensLabel(lensFacing: Int): String = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> "Rear camera"
        CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External camera"
        else -> "Camera"
    }
}

internal data class EncoderSupport(
    val codec: VideoCodec,
    val capabilities: MediaCodecInfo.VideoCapabilities,
) {
    fun supports(profile: QualityProfile): Boolean =
        capabilities.areSizeAndRateSupported(
            profile.width,
            profile.height,
            profile.framesPerSecond.toDouble(),
        )
}

private object EncoderInventory {
    fun hardwareVideoEncoders(): List<EncoderSupport> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.flatMap { info ->
            if (!info.isEncoder || !info.isHardwareAccelerated) return@flatMap emptyList()
            listOf(VideoCodec.HEVC, VideoCodec.AVC).mapNotNull { codec ->
                if (codec.mimeType !in info.supportedTypes) return@mapNotNull null
                try {
                    val videoCapabilities = info.getCapabilitiesForType(codec.mimeType).videoCapabilities
                        ?: return@mapNotNull null
                    EncoderSupport(codec, videoCapabilities)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
}
