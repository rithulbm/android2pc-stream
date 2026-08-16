package dev.localstream.sender.diagnostics

import java.io.IOException
import java.util.Locale

enum class SafeErrorCode {
    PERMISSION_DENIED,
    INVALID_PAIRING,
    CAMERA_UNAVAILABLE,
    ENCODER_UNAVAILABLE,
    AUDIO_UNAVAILABLE,
    LOCAL_NETWORK_BLOCKED,
    AUTHENTICATION_FAILED,
    RECEIVER_UNREACHABLE,
    TRANSPORT_TIMEOUT,
    QUEUE_FULL,
    THERMAL_STOP,
    INTERNAL_FAILURE,
}

data class DiagnosticEvent(
    val code: SafeErrorCode,
    val profileName: String?,
    val reconnectAttempt: Int?,
    val queueUtilizationPercent: Int?,
)

object SafeDiagnostics {
    /** Produces bounded local diagnostics from allowlisted values only. */
    fun format(event: DiagnosticEvent): String {
        val output = StringBuilder("code=").append(event.code.name)
        event.profileName?.takeIf { PROFILE_NAMES.contains(it) }?.let {
            output.append(" profile=").append(it)
        }
        event.reconnectAttempt?.coerceIn(0, 10)?.let {
            output.append(" reconnect_attempt=").append(it)
        }
        event.queueUtilizationPercent?.coerceIn(0, 100)?.let {
            output.append(" queue_percent=").append(it)
        }
        return output.toString()
    }

    /** Never propagates provider, endpoint, QR, JNI, or exception message text. */
    fun codeFor(throwable: Throwable): SafeErrorCode = when (throwable) {
        is SecurityException -> SafeErrorCode.PERMISSION_DENIED
        is IOException -> SafeErrorCode.RECEIVER_UNREACHABLE
        else -> SafeErrorCode.INTERNAL_FAILURE
    }

    private val PROFILE_NAMES = setOf(
        "AUTO",
        "UHD_60",
        "UHD_30",
        "QHD_60",
        "FHD_60",
        "FHD_30",
        "HD_30",
    ).map { it.uppercase(Locale.ROOT) }.toSet()
}

