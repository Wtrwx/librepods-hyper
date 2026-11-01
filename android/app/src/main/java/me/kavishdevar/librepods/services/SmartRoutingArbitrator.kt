/* LibrePods contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.bluetooth.HijackAnswer
import me.kavishdevar.librepods.bluetooth.SmartRoutingHijackResponse
import java.util.Locale
import kotlin.random.Random

/** One negotiation per local playback intent. Clock values are Android milliseconds,
 * never Apple's opaque mach-absolute-time hijackBackoffTicks. No automatic retry loop. */
internal class SmartRoutingArbitrator(
    private val now: () -> Long,
    initialRequestId: Long = Random.nextLong(1, 0x100000000L)
) {
    companion object { const val RESPONSE_TIMEOUT_MS = 4_000L }
    data class Request(val id: Long, val generation: Long, val peers: Set<String>, val forCall: Boolean)
    enum class Outcome { ACCEPTED, DENIED, TIMED_OUT }
    data class Result(val request: Request, val outcome: Outcome)

    private var nextId = initialRequestId.coerceIn(1, 0xffffffffL)
    private var deadline = 0L
    private var backoffUntil = 0L
    private val awaiting = mutableSetOf<String>()
    @Volatile var pending: Request? = null
        private set

    @Synchronized fun start(peers: Collection<String>, generation: Long, forCall: Boolean): Request? {
        if (now() < backoffUntil && !forCall) return null
        if (pending != null && (pending!!.forCall || !forCall)) return null
        val addresses = peers.map { it.uppercase(Locale.ROOT) }.toSet()
        require(addresses.isNotEmpty())
        val request = Request(nextId, generation, addresses, forCall)
        nextId = if (nextId == 0xffffffffL) 1 else nextId + 1
        pending = request
        awaiting.clear()
        awaiting.addAll(addresses)
        deadline = now() + RESPONSE_TIMEOUT_MS
        return request
    }

    @Synchronized fun answer(sender: String, response: SmartRoutingHijackResponse): Result? {
        val request = pending ?: return null
        val peer = sender.uppercase(Locale.ROOT)
        if (response.requestId != request.id || peer !in awaiting) return null
        if (now() >= deadline) return finish(Outcome.TIMED_OUT)
        if (response.answer != HijackAnswer.ACCEPTED) {
            // A local cooldown prevents callback storms. Apple ticks have no portable
            // conversion here, so do not use them as a millisecond duration.
            return finish(Outcome.DENIED)
        }
        awaiting.remove(peer)
        return if (awaiting.isEmpty()) finish(Outcome.ACCEPTED) else null
    }

    @Synchronized fun timeout(id: Long): Result? =
        if (pending?.id == id && now() >= deadline) finish(Outcome.TIMED_OUT) else null

    @Synchronized fun cancel() { pending = null; awaiting.clear() }
    @Synchronized fun reset() { cancel(); backoffUntil = 0L }

    private fun finish(outcome: Outcome): Result {
        val result = Result(checkNotNull(pending), outcome)
        if (outcome != Outcome.ACCEPTED) backoffUntil = now() + RESPONSE_TIMEOUT_MS
        cancel()
        return result
    }
}

internal object SmartRoutingPolicy {
    fun mayYieldForRemoteIntent(callActive: Boolean, manualReverse: Boolean = false): Boolean =
        !callActive || manualReverse

    fun answerRequest(callActive: Boolean, localRequestPending: Boolean): HijackAnswer = when {
        callActive -> HijackAnswer.REJECTED
        localRequestPending -> HijackAnswer.AMBIGUOUS
        else -> HijackAnswer.ACCEPTED
    }
}
