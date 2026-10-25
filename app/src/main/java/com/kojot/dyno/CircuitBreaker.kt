package com.kojot.dyno

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class TripKind { LOOP, TTFT_DEADLINE, STALL, WALL_DEADLINE, RUNAWAY, MEMORY_CRITICAL, ENGINE_DIED, USER_ABORT }

data class Trip(val kind: TripKind, val detail: JSONObject)

data class BreakerConfig(
    val ttftDeadlineMs: Long,
    val stallMs: Long,
    val wallDeadlineMs: Long,
    /** Hard cap on streamed chunks (~tokens). Trips RUNAWAY when exceeded. */
    val maxChunks: Int,
    val loopDetection: Boolean = true,
    /** A cycle must cover at least this many tokens (and at least 4 repeats) to count. */
    val loopMinSpan: Int = 48,
    val loopMaxPeriod: Int = 128,
)

/**
 * Per-turn watchdog. Feed it every streamed chunk via [onChunk] and tick it from a timer via [tick].
 * The first trip wins and invokes [onTrip] exactly once (typically: abort the HTTP call).
 *
 * Cycle detection: keeps a rolling window of token hashes and looks for the smallest period p such that
 * the last p*reps tokens are p-periodic, with reps = max(4, ceil(minSpan / p)). A 1-token attractor must
 * therefore repeat ~48x, a 10-token phrase 5x, a 50-token paragraph 4x.
 */
class CircuitBreaker(private val cfg: BreakerConfig, private val onTrip: (Trip) -> Unit) {

    private val tripped = AtomicReference<Trip?>(null)
    private val startNs = System.nanoTime()
    @Volatile private var firstTokenNs = 0L
    @Volatile private var lastActivityNs = startNs

    @Volatile var chunks = 0
        private set
    @Volatile var loopPeriod: Int? = null
        private set
    @Volatile var loopRepeats: Int? = null
        private set

    private val hist = IntArray(WINDOW)
    private var hn = 0

    val trip: Trip? get() = tripped.get()

    /** Reading thread only. */
    fun onChunk(text: String) {
        val now = System.nanoTime()
        if (firstTokenNs == 0L) firstTokenNs = now
        lastActivityNs = now
        chunks++
        if (chunks > cfg.maxChunks) {
            trip(TripKind.RUNAWAY, JSONObject().put("chunks", chunks).put("max_chunks", cfg.maxChunks)); return
        }
        if (!cfg.loopDetection) return
        if (hn == WINDOW) { System.arraycopy(hist, WINDOW / 2, hist, 0, WINDOW / 2); hn = WINDOW / 2 }
        hist[hn++] = text.hashCode()
        if (chunks % 4 == 0) detectCycle()?.let { (p, reps) ->
            loopPeriod = p; loopRepeats = reps
            trip(TripKind.LOOP, JSONObject().put("period_tokens", p).put("repeats", reps).put("at_chunk", chunks)
                .put("tail", text))
        }
    }

    /** Watchdog thread, ~4 Hz. */
    fun tick() {
        if (tripped.get() != null) return
        val now = System.nanoTime()
        val sinceStartMs = (now - startNs) / 1_000_000
        if (sinceStartMs > cfg.wallDeadlineMs) {
            trip(TripKind.WALL_DEADLINE, JSONObject().put("elapsed_ms", sinceStartMs)); return
        }
        if (firstTokenNs == 0L) {
            if (sinceStartMs > cfg.ttftDeadlineMs) trip(TripKind.TTFT_DEADLINE, JSONObject().put("waited_ms", sinceStartMs))
        } else {
            val gapMs = (now - lastActivityNs) / 1_000_000
            if (gapMs > cfg.stallMs) trip(TripKind.STALL, JSONObject().put("gap_ms", gapMs).put("chunks", chunks))
        }
    }

    /** Any thread. Returns true if this call was the one that tripped the breaker. */
    fun trip(kind: TripKind, detail: JSONObject = JSONObject()): Boolean {
        val t = Trip(kind, detail)
        if (!tripped.compareAndSet(null, t)) return false
        onTrip(t)
        return true
    }

    private fun detectCycle(): Pair<Int, Int>? {
        val n = hn
        val maxP = min(cfg.loopMaxPeriod, n / 2)
        for (p in 1..maxP) {
            val reps = max(4, ceil(cfg.loopMinSpan / p.toDouble()).toInt())
            val span = p * reps
            if (span > n) continue
            var ok = true
            var i = n - 1
            val lo = n - span + p
            while (i >= lo) { if (hist[i] != hist[i - p]) { ok = false; break }; i-- }
            if (!ok) continue
            var j = lo - 1                      // extend backwards to report the true repeat count
            var extra = 0
            while (j - p >= 0 && hist[j] == hist[j - p]) { j--; extra++ }
            return p to (reps + extra / p)
        }
        return null
    }

    private companion object { const val WINDOW = 1024 }
}
