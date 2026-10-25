package com.kojot.dyno

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Owns exactly one llama-server child process.
 *
 * The binary must ship as jniLibs/arm64-v8a/libllama_server.so with extractNativeLibs=true: Android 10+
 * forbids exec() from app-writable directories (W^X), but allows exec from nativeLibraryDir.
 *
 * Failure model handled here:
 *  - app killed while child lives  -> orphan holds the port and RAM: [reapOrphans] kills it on next start
 *  - child killed by LMK / phantom-process killer / crash -> waiter coroutine publishes [ExitInfo]
 *  - child wedged on SIGTERM -> escalate to SIGKILL, then wait for the port to be released
 */
class LlamaServerProcess(private val ctx: Context, private val scope: CoroutineScope) {

    enum class State { STOPPED, STARTING, READY, STOPPING, DEAD }
    enum class LogSignal { ALLOC_FAIL, GGML_ASSERT, CTX_EXCEEDED, GRAMMAR_ERROR, ABORT }

    data class ExitInfo(val exitCode: Int, val signal: Int?, val atWallMs: Long, val expected: Boolean, val uptimeMs: Long)
    data class LogEvent(val tsMs: Long, val line: String, val signal: LogSignal?)
    data class StopResult(val escalatedToKill: Boolean, val tookMs: Long, val portReleased: Boolean)

    sealed interface StartResult {
        data class Ready(val pid: Int, val loadMs: Long, val buildInfo: String?, val nCtx: Int?) : StartResult
        data class Failed(val reason: String, val detail: String?, val exit: ExitInfo?, val logTail: String) : StartResult
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _exits = MutableSharedFlow<ExitInfo>(extraBufferCapacity = 8)
    val exits: SharedFlow<ExitInfo> = _exits.asSharedFlow()
    private val _logs = MutableSharedFlow<LogEvent>(extraBufferCapacity = 2048, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val logs: SharedFlow<LogEvent> = _logs.asSharedFlow()

    @Volatile private var process: Process? = null
    @Volatile var pid: Int? = null
        private set
    @Volatile var lastExit: ExitInfo? = null
        private set
    @Volatile private var port = 8080
    @Volatile private var expectedStop = false
    private var startedAtElapsed = 0L
    private var readerJob: Job? = null
    private var waiterJob: Job? = null

    private val tail = ArrayDeque<String>()
    private val signals: MutableSet<LogSignal> = ConcurrentHashMap.newKeySet()

    private val nativeDir get() = ctx.applicationInfo.nativeLibraryDir
    private val workDir get() = File(ctx.filesDir, "engine").apply { mkdirs() }
    private val pidFile get() = File(workDir, "llama_server.pid")

    fun isAlive(): Boolean = process?.isAlive == true
    fun logTail(n: Int = 40): String = synchronized(tail) { tail.takeLast(n).joinToString("\n") }
    /** Signals seen in the server log since the last drain (used to classify a death). */
    fun drainSignals(): Set<LogSignal> = signals.toSet().also { signals.clear() }

    // ---- start ---------------------------------------------------------------------------------------

    suspend fun start(cfg: LaunchConfig): StartResult = withContext(Dispatchers.IO) {
        check(_state.value == State.STOPPED || _state.value == State.DEAD) { "start() while ${_state.value}" }
        _state.value = State.STARTING
        port = cfg.port
        lastExit = null
        signals.clear()
        synchronized(tail) { tail.clear() }

        reapOrphans(exceptPid = null)
        if (!awaitPortFree(cfg.port, 6_000)) {
            _state.value = State.STOPPED
            return@withContext StartResult.Failed("PORT_BUSY", "127.0.0.1:${cfg.port} still bound after orphan reap", null, "")
        }

        val bin = File(nativeDir, BIN_NAME)
        if (!bin.exists()) {
            _state.value = State.STOPPED
            return@withContext StartResult.Failed("BINARY_MISSING", "${bin.path} not found (jniLibs / extractNativeLibs?)", null, "")
        }

        val pb = ProcessBuilder(buildCommand(bin, cfg)).directory(workDir).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir + (System.getenv("LD_LIBRARY_PATH")?.let { ":$it" } ?: "")
        expectedStop = false
        val p = try { pb.start() } catch (e: IOException) {
            _state.value = State.STOPPED
            return@withContext StartResult.Failed("EXEC_FAILED", e.message, null, "")
        }
        process = p
        val childPid = processPid(p)
        pid = childPid
        startedAtElapsed = SystemClock.elapsedRealtime()
        try { pidFile.writeText(childPid.toString()) } catch (_: IOException) {}

        readerJob = scope.launch(Dispatchers.IO) { pumpLogs(p) }
        waiterJob = scope.launch(Dispatchers.IO) { onExit(p.waitFor()) }

        // readiness: /health is 503 while the model loads, 200 {"status":"ok"} when serving
        val timeoutMs = cfg.readyTimeoutMs ?: 120_000L
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (_state.value == State.DEAD || !p.isAlive) {
                waitForState(State.DEAD, 1_000)
                return@withContext StartResult.Failed("DIED_DURING_LOAD", "exit=${lastExit?.exitCode}", lastExit, logTail())
            }
            val h = LocalHttp.request(cfg.port, "GET", "/health", connectMs = 500, readMs = 1000)
            if (h.status == 200) {
                val loadMs = SystemClock.elapsedRealtime() - startedAtElapsed
                _state.value = State.READY
                val props = LocalHttp.request(cfg.port, "GET", "/props", readMs = 3000)
                var build: String? = null
                var nCtx: Int? = null
                if (props.status == 200 && props.body != null) try {
                    val j = JSONObject(props.body)
                    build = j.optString("build_info").takeIf { it.isNotEmpty() }
                    nCtx = j.optJSONObject("default_generation_settings")?.optInt("n_ctx")?.takeIf { it > 0 }
                } catch (_: JSONException) {}
                return@withContext StartResult.Ready(childPid, loadMs, build, nCtx)
            }
            delay(250)
        }
        // never became ready: tear it down so we do not leak a half-loaded model
        stop(graceMs = 1_500)
        StartResult.Failed("READY_TIMEOUT", "no /health 200 within ${timeoutMs}ms", lastExit, logTail())
    }

    private fun buildCommand(bin: File, c: LaunchConfig): List<String> {
        val a = mutableListOf(
            bin.absolutePath,
            "--model", c.modelPath,
            "--host", "127.0.0.1", "--port", c.port.toString(),
            "-c", c.ctxSize.toString(),
            "-t", c.threads.toString(), "-tb", c.threadsBatch.toString(),
            "-b", c.batchSize.toString(), "-ub", c.ubatchSize.toString(),
            "-np", c.parallel.toString(),
            "--cache-type-k", c.cacheTypeK, "--cache-type-v", c.cacheTypeV,
            "-ngl", c.nGpuLayers.toString(),
            "--metrics", "--slots",
            "--timeout", "3600",
        )
        if (!c.mmap) a += "--no-mmap"
        if (c.mlock) a += "--mlock"
        c.flashAttn?.let { a += listOf("-fa", it) }
        c.ropeFreqBase?.let { a += listOf("--rope-freq-base", it.toString()) }
        c.ropeFreqScale?.let { a += listOf("--rope-freq-scale", it.toString()) }
        a += c.extraArgs
        return a
    }

    // ---- stop / kill ------------------------------------------------------------------------------

    /**
     * Graceful teardown: SIGTERM, wait [graceMs], escalate to SIGKILL, then wait for the listening socket to
     * be released so the next model can bind the same port. Safe to call in any state (incl. DEAD).
     */
    suspend fun stop(graceMs: Long = 4_000): StopResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        val p = process
        var escalated = false
        if (p != null && p.isAlive) {
            _state.value = State.STOPPING
            expectedStop = true
            signal(processPid(p), OsConstants.SIGTERM)
            if (!p.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                escalated = true
                signal(processPid(p), OsConstants.SIGKILL)
                p.waitFor(3, TimeUnit.SECONDS)
            }
            waitForState(State.STOPPED, 2_000)
        }
        readerJob?.cancel(); readerJob = null
        waiterJob?.cancel(); waiterJob = null
        val released = awaitPortFree(port, 5_000)
        try { pidFile.delete() } catch (_: Throwable) {}
        process = null; pid = null
        _state.value = State.STOPPED
        StopResult(escalated, SystemClock.elapsedRealtime() - t0, released)
    }

    /** Non-suspending SIGKILL for onDestroy and watchdog paths. Marks the exit as deliberate. */
    fun killNow() {
        expectedStop = true
        process?.let { signal(processPid(it), OsConstants.SIGKILL) }
    }

    // ---- orphans ---------------------------------------------------------------------------------

    /**
     * Kills any llama-server left behind by a previous app process. Android mounts /proc with hidepid, so an
     * app only sees its own UID's processes: scanning for our binary name is both sufficient and safe.
     */
    fun reapOrphans(exceptPid: Int? = null): List<Int> {
        val killed = ArrayList<Int>()
        val me = android.os.Process.myPid()
        File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me || pid == exceptPid) return@forEach
            val cmd = try { FileInputStream(File(d, "cmdline")).use { String(it.readBytes(), Charsets.ISO_8859_1) } } catch (_: Throwable) { return@forEach }
            if (cmd.contains(BIN_NAME)) {
                signal(pid, OsConstants.SIGKILL)
                killed += pid
            }
        }
        try { if (pidFile.exists()) pidFile.delete() } catch (_: Throwable) {}
        return killed
    }

    // ---- internals --------------------------------------------------------------------------------

    private fun onExit(code: Int) {
        val sig = if (code > 128) code - 128 else null
        val info = ExitInfo(code, sig, System.currentTimeMillis(), expectedStop, SystemClock.elapsedRealtime() - startedAtElapsed)
        lastExit = info
        try { pidFile.delete() } catch (_: Throwable) {}
        _state.value = if (expectedStop) State.STOPPED else State.DEAD
        _exits.tryEmit(info)
    }

    private fun pumpLogs(p: Process) {
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val sig = classify(line)
                    sig?.let { signals += it }
                    synchronized(tail) { tail.addLast(line); while (tail.size > TAIL_MAX) tail.removeFirst() }
                    _logs.tryEmit(LogEvent(System.currentTimeMillis(), line, sig))
                }
            }
        } catch (_: IOException) { /* stream closed on exit */ }
    }

    private fun classify(line: String): LogSignal? {
        val l = line.lowercase()
        return when {
            "ggml_assert" in l -> LogSignal.GGML_ASSERT
            "failed to allocate" in l || "unable to allocate" in l || "bad_alloc" in l || "out of memory" in l -> LogSignal.ALLOC_FAIL
            "exceeds the available context size" in l || "input is too large" in l || "context size exceeded" in l -> LogSignal.CTX_EXCEEDED
            "empty grammar stack" in l || "failed to parse grammar" in l || "unexpected empty grammar" in l -> LogSignal.GRAMMAR_ERROR
            "terminate called" in l || "abort()" in l -> LogSignal.ABORT
            else -> null
        }
    }

    private suspend fun waitForState(target: State, timeoutMs: Long) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (_state.value != target && SystemClock.elapsedRealtime() < end) delay(25)
    }

    /** Loopback bind probe. SO_REUSEADDR lets us ignore TIME_WAIT but not a live listener. */
    private fun portFree(p: Int): Boolean = try {
        ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), p))
            true
        }
    } catch (_: IOException) { false }

    private suspend fun awaitPortFree(p: Int, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (portFree(p)) return true
            delay(100)
        }
        return portFree(p)
    }

    
    /**
     * Resolve the native OS pid of a [Process].
     * [Process.pid] is API 31+ in the Android SDK stubs; we always use reflection so this
     * compiles against any compileSdk and runs on minSdk 28+.
     */
    @Suppress("DiscouragedPrivateApi")
    private fun processPid(p: Process): Int {
        // API 31+: public long pid()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val m = Process::class.java.getMethod("pid")
                val v = m.invoke(p)
                when (v) {
                    is Int -> return v
                    is Long -> return v.toInt()
                }
            } catch (_: Throwable) { /* fall through */ }
        }
        // Hidden field on Android's ProcessImpl / older UNIXProcess
        var cls: Class<*>? = p.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField("pid")
                f.isAccessible = true
                return f.getInt(p)
            } catch (_: Throwable) {
                cls = cls.superclass
            }
        }
        return -1
    }

    private fun signal(pid: Int, sig: Int) {
        // pid <= 0 is invalid here (reflection failure returns -1); never call kill(-1/0).
        if (pid <= 0) return
        try { Os.kill(pid, sig) } catch (_: ErrnoException) { /* ESRCH: already gone */ }
    }

    companion object {
        const val BIN_NAME = "libllama_server.so"
        private const val TAIL_MAX = 400
    }
}
