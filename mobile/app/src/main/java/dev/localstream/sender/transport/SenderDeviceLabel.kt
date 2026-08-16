package dev.localstream.sender.transport

import android.os.Build

/** Produces bounded, non-unique display metadata for the paired OBS receiver. */
object SenderDeviceLabel {
    private const val FALLBACK = "Android phone"
    private const val MAX_LENGTH = 48

    fun current(): String = from(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())

    fun from(manufacturer: String, model: String): String {
        val trimmedManufacturer = clean(manufacturer)
        val trimmedModel = clean(model)
        val combined = when {
            trimmedModel.isEmpty() -> trimmedManufacturer
            trimmedManufacturer.isEmpty() -> trimmedModel
            trimmedModel.startsWith(trimmedManufacturer, ignoreCase = true) -> trimmedModel
            else -> "$trimmedManufacturer $trimmedModel"
        }
        return combined.ifEmpty { FALLBACK }.take(MAX_LENGTH).trim().ifEmpty { FALLBACK }
    }

    private fun clean(value: String): String = value
        .map { character ->
            if (character.isAsciiDeviceLabelCharacter()) character else ' '
        }
        .joinToString(separator = "")
        .trim()
        .replace(Regex(" +"), " ")

    private fun Char.isAsciiDeviceLabelCharacter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == ' ' || this == '.' || this == '_' || this == '-'
}
