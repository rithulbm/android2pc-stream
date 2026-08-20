package dev.localstream.sender.quality

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.roundToInt

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
                val encoder = if (cameraSupported) chooseEncoder(encoders, profile) else null
                when {
                    !cameraSupported -> ProfileCapability(
                        profile,
                        null,
                        false,
                        "This camera cannot capture ${profile.displayName}.",
                    )

                    encoder == null -> ProfileCapability(
                        profile,
                        null,
                        false,
                        "This phone has no hardware encoder for ${profile.displayName} at a supported bitrate.",
                    )

                    else -> ProfileCapability(
                        profile = profile,
                        codec = encoder.codec,
                        available = true,
                        unavailableReason = null,
                        constrainedHighSpeed = !regularSupported && highSpeedSupported,
                        encoderName = encoder.encoderName,
                        bitrate = encoder.bitrate,
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
        return (1_000_000_000.0 / duration.toDouble())
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .roundToInt()
    }

    private fun chooseEncoder(encoders: List<EncoderSupport>, profile: QualityProfile): EncoderChoice? {
        for (codec in listOf(VideoCodec.HEVC, VideoCodec.AVC)) {
            for (encoder in encoders) {
                if (encoder.codec != codec) continue
                val bitrate = encoder.bitrateFor(profile) ?: continue
                return EncoderChoice(codec, encoder.encoderName, bitrate)
            }
        }
        return null
    }

    private fun lensLabel(lensFacing: Int): String = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> "Rear camera"
        CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External camera"
        else -> "Camera"
    }
}

private data class EncoderChoice(
    val codec: VideoCodec,
    val encoderName: String,
    val bitrate: Int,
)

internal data class EncoderSupport(
    val encoderName: String,
    val codec: VideoCodec,
    val capabilities: MediaCodecInfo.VideoCapabilities,
) {
    fun bitrateFor(profile: QualityProfile): Int? {
        if (!capabilities.areSizeAndRateSupported(
                profile.width,
                profile.height,
                profile.framesPerSecond.toDouble(),
            )
        ) {
            return null
        }
        val supported = capabilities.bitrateRange
        val minimum = maxOf(profile.minimumBitrate(codec), supported.lower)
        val maximum = minOf(profile.maximumBitrate(codec), supported.upper)
        if (minimum > maximum) return null
        return profile.targetBitrate(codec).coerceIn(minimum, maximum)
    }
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
                    EncoderSupport(info.name, codec, videoCapabilities)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
}
