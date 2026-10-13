package ai.impact.data

/**
 * Sliding-window rate limiter with a minimum gap between actions.
 *
 * TinyAI has no network access, so this isn't about remote attackers: it stops automated or
 * runaway input (accessibility/macro tools, injected taps, a stuck key) from hammering the
 * on-device model or flooding local storage.
 */
class RateLimiter(
    private val maxEvents: Int,
    private val windowMillis: Long,
    private val minIntervalMillis: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val timestamps = ArrayDeque<Long>()

    sealed interface Result {
        data object Allowed : Result
        /** Denied; the caller may retry after [retryAfterMillis]. */
        data class Limited(val retryAfterMillis: Long) : Result
    }

    /** Records an event if allowed. A denied attempt is not recorded, so it never extends the wait. */
    @Synchronized
    fun tryAcquire(): Result {
        val now = clock()
        while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMillis) timestamps.removeFirst()

        val last = timestamps.lastOrNull()
        if (last != null && now - last < minIntervalMillis) {
            return Result.Limited(minIntervalMillis - (now - last))
        }
        if (timestamps.size >= maxEvents) {
            return Result.Limited(windowMillis - (now - timestamps.first()))
        }
        timestamps.addLast(now)
        return Result.Allowed
    }
}

/** App-wide abuse limits, kept in one place so they're easy to review and tune. */
object Limits {
    /** Longest message a user can send. Keeps prompts inside the model's 2,048-token context. */
    const val MAX_MESSAGE_CHARS = 2_000

    /** Show the character counter once a message gets this close to the limit. */
    const val COUNTER_THRESHOLD = 1_600

    /** Upper bound on stored memories, so auto-learning can't grow the database forever. */
    const val MAX_MEMORIES = 300

    fun messageLimiter() = RateLimiter(maxEvents = 15, windowMillis = 60_000, minIntervalMillis = 1_000)
    fun rememberLimiter() = RateLimiter(maxEvents = 10, windowMillis = 60_000)
}
