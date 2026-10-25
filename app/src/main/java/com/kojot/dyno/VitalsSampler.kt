package com.kojot.dyno

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor

/** One instantaneous reading. Fields that the OS/SELinux refuses to expose are null, never fabricated. */
data class Vitals(
    val monoUs: Long,
    val wallMs: Long,
    // child process (llama-server)
    val rssMb: Float?, val rssAnonMb: Float?, val rssFileMb: Float?, val hwmMb: Float?, val swapMb: Float?,
    val oomScoreAdj: Int?,
    // system memory
    val memAvailMb: Float,
    /** ActivityManager availMem minus the low-memory threshold: how far we are from the LMK line. */
    val lmkHeadroomMb: Float,
    val lowMemory: Boolean,
    val swapFreeMb: Float?,
    val psiSomeAvg10: Float?, val psiFullAvg10: Float?,
    // thermals
    val batteryTempC: Float?, val batteryPct: Int?, val charging: Boolean?,
    val thermalStatus: Int, val thermalHeadroom: Float?,
    /** min over CPUs of scaling_max_freq / cpuinfo_max_freq: <1 means a thermal/OS frequency cap is active. */
    val cpuFreqCapRatio: Float?,
) {
    val throttling: Boolean
        get() = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            (thermalHeadroom ?: 0f) >= 1.0f || (cpuFreqCapRatio ?: 1f) < 0.85f
}

/** Aggregates samples taken during one turn. Thread-safe. */
class VitalsAgg {
    private var first: Vitals? = null
    private var last: Vitals? = null
    private var samples = 0
    private var peakRss = 0f; private var peakAnon = 0f; private var peakFile = 0f; private var hwm = 0f
    private var memAvailMin = Float.MAX_VALUE; private var headroomMin = Float.MAX_VALUE
    private var psiSomeMax = 0f; private var psiFullMax = 0f
    private var battMax = -Float.MAX_VALUE
    private var thermalMax = 0; private var throttled = false
    private var headroomMaxThermal = 0f; private var capMin = Float.MAX_VALUE

    @Synchronized fun update(v: Vitals) {
        if (first == null) first = v
        last = v; samples++
        v.rssMb?.let { peakRss = maxOf(peakRss, it) }
        v.rssAnonMb?.let { peakAnon = maxOf(peakAnon, it) }
        v.rssFileMb?.let { peakFile = maxOf(peakFile, it) }
        v.hwmMb?.let { hwm = maxOf(hwm, it) }
        memAvailMin = minOf(memAvailMin, v.memAvailMb)
        headroomMin = minOf(headroomMin, v.lmkHeadroomMb)
        v.psiSomeAvg10?.let { psiSomeMax = maxOf(psiSomeMax, it) }
        v.psiFullAvg10?.let { psiFullMax = maxOf(psiFullMax, it) }
        v.batteryTempC?.let { battMax = maxOf(battMax, it) }
        thermalMax = maxOf(thermalMax, v.thermalStatus)
        throttled = throttled || v.throttling
        v.thermalHeadroom?.let { headroomMaxThermal = maxOf(headroomMaxThermal, it) }
        v.cpuFreqCapRatio?.let { capMin = minOf(capMin, it) }
    }

    @Synchronized fun lastSample(): Vitals? = last

    /** Peak RSS: the larger of the sampled peak and the kernel's per-turn VmHWM (reset at window start when permitted). */
    @Synchronized fun toContentValues(): ContentValues {
        val f = first; val l = last
        return cvOf(
            "start_rss_mb" to f?.rssMb, "peak_rss_mb" to maxOf(peakRss, hwm).takeIf { it > 0f }, "end_rss_mb" to l?.rssMb,
            "rss_anon_peak_mb" to peakAnon.takeIf { it > 0f }, "rss_file_peak_mb" to peakFile.takeIf { it > 0f },
            "mem_available_mb_start" to f?.memAvailMb, "mem_available_mb_min" to memAvailMin.takeIf { samples > 0 },
            "lmk_headroom_mb_min" to headroomMin.takeIf { samples > 0 },
            "psi_some_max" to psiSomeMax.takeIf { samples > 0 && (f?.psiSomeAvg10 != null) },
            "psi_full_max" to psiFullMax.takeIf { samples > 0 && (f?.psiFullAvg10 != null) },
            "battery_temp_c" to f?.batteryTempC, "battery_temp_c_end" to l?.batteryTempC,
            "battery_temp_c_max" to battMax.takeIf { it > -Float.MAX_VALUE },
            "thermal_status" to f?.thermalStatus, "thermal_status_max" to thermalMax.takeIf { samples > 0 },
            "throttling_flag" to throttled, "thermal_headroom_max" to headroomMaxThermal.takeIf { samples > 0 && f?.thermalHeadroom != null },
            "cpu_freq_cap_ratio_min" to capMin.takeIf { it < Float.MAX_VALUE }, "vitals_samples" to samples,
        )
    }

    /** Minimal columns refreshed by the 1 Hz heartbeat so a kill mid-turn still leaves evidence. */
    @Synchronized fun toHeartbeat(chunks: Int): ContentValues = cvOf(
        "heartbeat_ms" to System.currentTimeMillis(),
        "peak_rss_mb" to maxOf(peakRss, hwm).takeIf { it > 0f },
        "mem_available_mb_min" to memAvailMin.takeIf { samples > 0 },
        "lmk_headroom_mb_min" to headroomMin.takeIf { samples > 0 },
        "stream_chunks" to chunks,
    )
}

class VitalsWindow internal constructor(
    private val sampler: VitalsSampler,
    private val pidProvider: () -> Int?,
    private val scope: CoroutineScope,
    periodMs: Long,
    private val onSample: (Vitals) -> Unit,
) {
    val agg = VitalsAgg()
    private val job: Job

    init {
        pidProvider()?.let { sampler.resetHwm(it) }
        val v0 = sampler.snapshot(pidProvider()); agg.update(v0); onSample(v0)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(periodMs)
                val v = sampler.snapshot(pidProvider())
                agg.update(v)
                onSample(v)
            }
        }
    }

    /** Stops sampling, takes a final reading and returns the aggregate. */
    fun stop(): VitalsAgg {
        job.cancel()
        agg.update(sampler.snapshot(pidProvider()))
        return agg
    }
}

class VitalsSampler(private val ctx: Context) {

    private val am = ctx.getSystemService(ActivityManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    private val mi = ActivityManager.MemoryInfo()

    // getThermalHeadroom() is rate-limited by the platform (~1 Hz); cache between calls
    @Volatile private var headroomAtMs = 0L
    @Volatile private var headroomCache: Float? = null
    @Volatile private var capAtMs = 0L
    @Volatile private var capCache: Float? = null

    fun window(pidProvider: () -> Int?, periodMs: Long, scope: CoroutineScope, onSample: (Vitals) -> Unit = {}) =
        VitalsWindow(this, pidProvider, scope, periodMs, onSample)

    /** Resets the kernel's VmHWM to current RSS so peak_rss_mb is per turn, not per process. Best effort. */
    fun resetHwm(pid: Int) {
        try { File("/proc/$pid/clear_refs").writeText("5") } catch (_: Throwable) { /* SELinux may deny; sampled peak still works */ }
    }

    @Synchronized
    fun snapshot(pid: Int?): Vitals {
        val st = pid?.let { readProcStatus(it) }
        am.getMemoryInfo(mi)
        val meminfo = readMemInfo()
        val availMb = meminfo["MemAvailable"]?.let { it / 1024f } ?: (mi.availMem / MB)
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val (some, full) = readPsi()
        return Vitals(
            monoUs = SystemClock.elapsedRealtimeNanos() / 1000, wallMs = System.currentTimeMillis(),
            rssMb = st?.get("VmRSS")?.let { it / 1024f }, rssAnonMb = st?.get("RssAnon")?.let { it / 1024f },
            rssFileMb = st?.get("RssFile")?.let { it / 1024f }, hwmMb = st?.get("VmHWM")?.let { it / 1024f },
            swapMb = st?.get("VmSwap")?.let { it / 1024f },
            oomScoreAdj = pid?.let { readInt("/proc/$it/oom_score_adj") },
            memAvailMb = availMb,
            lmkHeadroomMb = (mi.availMem - mi.threshold) / MB,
            lowMemory = mi.lowMemory,
            swapFreeMb = meminfo["SwapFree"]?.let { it / 1024f },
            psiSomeAvg10 = some, psiFullAvg10 = full,
            batteryTempC = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 },
            thermalStatus = pm.currentThermalStatus,
            thermalHeadroom = thermalHeadroom(),
            cpuFreqCapRatio = cpuCapRatio(),
        )
    }

    private fun thermalHeadroom(): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now - headroomAtMs < 1100) return headroomCache
        headroomAtMs = now
        headroomCache = try { pm.getThermalHeadroom(10).takeIf { !it.isNaN() } } catch (_: Throwable) { null }
        return headroomCache
    }

    private fun cpuCapRatio(): Float? {
        val now = SystemClock.elapsedRealtime()
        if (now - capAtMs < 1000) return capCache
        capAtMs = now
        var min = Float.MAX_VALUE
        for (c in 0 until cpuCount) {
            val base = "/sys/devices/system/cpu/cpu$c/cpufreq"
            val max = readInt("$base/cpuinfo_max_freq") ?: continue
            val cap = readInt("$base/scaling_max_freq") ?: continue
            if (max > 0) min = minOf(min, cap.toFloat() / max)
        }
        capCache = min.takeIf { it < Float.MAX_VALUE }?.coerceAtMost(1f)
        return capCache
    }

    private fun readProcStatus(pid: Int): Map<String, Long>? = try {
        val m = HashMap<String, Long>(12)
        File("/proc/$pid/status").forEachLine { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i)
                if (key in STATUS_KEYS) m[key] = line.substring(i + 1).trim().substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        m.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    private fun readMemInfo(): Map<String, Long> = try {
        val m = HashMap<String, Long>(4)
        File("/proc/meminfo").forEachLine { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val key = line.substring(0, i)
                if (key == "MemAvailable" || key == "SwapFree") m[key] = line.substring(i + 1).trim().substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        m
    } catch (_: Throwable) { emptyMap() }

    /** PSI (/proc/pressure/memory). Frequently denied to apps by SELinux: null then. */
    private fun readPsi(): Pair<Float?, Float?> = try {
        var some: Float? = null; var full: Float? = null
        File("/proc/pressure/memory").forEachLine { line ->
            val avg = AVG10.find(line)?.groupValues?.get(1)?.toFloatOrNull()
            if (line.startsWith("some")) some = avg else if (line.startsWith("full")) full = avg
        }
        some to full
    } catch (_: Throwable) { null to null }

    private fun readInt(path: String): Int? = try { File(path).readText().trim().toIntOrNull() } catch (_: Throwable) { null }

    private companion object {
        const val MB = 1024f * 1024f
        val STATUS_KEYS = setOf("VmRSS", "VmHWM", "VmSwap", "RssAnon", "RssFile", "RssShmem")
        val AVG10 = Regex("avg10=([0-9.]+)")
    }
}

/** How much of a file is already in the kernel page cache (mincore). Distinguishes cold vs warm model loads. */
object PageCache {
    /** Fraction 0..1 of the file's pages resident right now, or null if mmap/mincore is unavailable. */
    fun residentFraction(file: File): Double? {
        val len = file.length()
        if (len <= 0) return null
        var fd: FileDescriptor? = null
        var addr = 0L
        return try {
            fd = Os.open(file.absolutePath, OsConstants.O_RDONLY, 0)
            addr = Os.mmap(0, len, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, 0)
            val page = Os.sysconf(OsConstants._SC_PAGESIZE)
            val pages = ((len + page - 1) / page).toInt()
            val vec = ByteArray(pages)
            Os.mincore(addr, len, vec)
            vec.count { (it.toInt() and 1) != 0 }.toDouble() / pages
        } catch (_: ErrnoException) { null } catch (_: Throwable) { null } finally {
            if (addr != 0L) try { Os.munmap(addr, len) } catch (_: Throwable) {}
            fd?.let { try { Os.close(it) } catch (_: Throwable) {} }
        }
    }
}
