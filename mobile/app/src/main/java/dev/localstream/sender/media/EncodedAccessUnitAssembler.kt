package dev.localstream.sender.media

/** One complete encoded MediaCodec output access unit. */
data class EncodedAccessUnit(
    val bytes: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

/**
 * Bounded assembler for codecs that split one encoded frame across multiple output buffers.
 *
 * MediaCodec marks every non-final fragment with BUFFER_FLAG_PARTIAL_FRAME. The final fragment
 * has that bit clear. Flags from all fragments are preserved so key-frame/config markers cannot
 * be lost when a vendor codec places them only on the first fragment.
 */
class EncodedAccessUnitAssembler(private val maximumBytes: Int) {
    private var pending = ByteArray(0)
    private var pendingPresentationTimeUs = 0L
    private var pendingFlags = 0

    fun offer(bytes: ByteArray, presentationTimeUs: Long, flags: Int, partialFrame: Boolean): EncodedAccessUnit? {
        require(bytes.isNotEmpty()) { "encoded fragment is empty" }
        require(presentationTimeUs >= 0L) { "encoded fragment timestamp is negative" }
        val combinedSize = pending.size.toLong() + bytes.size.toLong()
        require(combinedSize in 1..maximumBytes.toLong()) { "encoded access unit exceeds bound" }

        val hadPending = pending.isNotEmpty()
        val timestamp = if (hadPending) pendingPresentationTimeUs else presentationTimeUs
        val combinedFlags = pendingFlags or flags
        val combined = ByteArray(combinedSize.toInt())
        if (hadPending) pending.copyInto(combined)
        bytes.copyInto(combined, pending.size)

        pending.fill(0)
        pending = ByteArray(0)
        pendingPresentationTimeUs = 0L
        pendingFlags = 0

        if (partialFrame) {
            pending = combined
            pendingPresentationTimeUs = timestamp
            pendingFlags = combinedFlags
            return null
        }
        return EncodedAccessUnit(combined, timestamp, combinedFlags)
    }

    fun clear() {
        pending.fill(0)
        pending = ByteArray(0)
        pendingPresentationTimeUs = 0L
        pendingFlags = 0
    }
}
