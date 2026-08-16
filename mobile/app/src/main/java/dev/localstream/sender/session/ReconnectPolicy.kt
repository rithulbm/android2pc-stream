package dev.localstream.sender.session

class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
    private val maximumAttempts: Int = 10,
    private val maximumElapsedMs: Long = 5L * 60L * 1_000L,
) {
    fun delayMs(attempt: Int, elapsedMs: Long): Long? {
        if (attempt < 0 || attempt >= maximumAttempts || elapsedMs < 0L || elapsedMs >= maximumElapsedMs) {
            return null
        }
        val shift = attempt.coerceAtMost(20)
        val exponential = baseDelayMs * (1L shl shift)
        return exponential.coerceAtMost(maximumDelayMs)
    }
}

