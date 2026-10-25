package com.kojot.dyno

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * JavascriptInterface exposing [DynoEngine] to the WebView SPA.
 *
 * Contract:
 *  - Every public method is `@JavascriptInterface` and returns a JSON string immediately
 *    (or a request-id for long ops). Long ops push results / stream events via
 *    [postEvent] → `window.__dyno_onEvent(json)`.
 *  - The SPA never blocks the UI thread waiting on native; it reacts to events.
 *
 * Threading: WebView calls arrive on a binder thread. We hop to [scope] for all engine work
 * and marshal event delivery onto the main looper (WebView.evaluateJavascript requirement).
 */
class NativeBridge(
    private val engine: DynoEngine,
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val onNotificationText: (String) -> Unit = {},
    private val activityProvider: () -> MainActivity? = { null },
) {
    private val main = Handler(Looper.getMainLooper())
    private var eventsBound = false

    fun bindEvents() {
        if (eventsBound) return
        eventsBound = true
        scope.launch {
            engine.events.collect { ev ->
                postEvent(serializeEvent(ev))
                when (ev) {
                    is DynoEvent.RunStatus -> onNotificationText("Run ${ev.runId}: ${ev.status}")
                    is DynoEvent.TurnStarted -> onNotificationText("Turn #${ev.turnIndex} ${ev.caseId}")
                    is DynoEvent.EngineState -> onNotificationText("Engine ${ev.state}")
                    else -> {}
                }
            }
        }
    }

    // ---- sync-ish queries (return JSON string) -------------------------------------------------------

    @JavascriptInterface
    fun listModels(dir: String): String {
        return scope.launchBlocking {
            val act = activityProvider()
            val hasAccess = act?.hasAllFilesAccess() ?: true
            if (!hasAccess) {
                // Kick the settings screen; JS shows the message
                main.post { act?.ensureAllFilesAccess() }
                return@launchBlocking JSONObject()
                    .put("ok", false)
                    .put("error", "ALL_FILES_REQUIRED")
                    .put("message", "Android blocked access to shared storage. Enable “All files access” for Kojot Dyno, then tap Scan again.")
                    .put("models", JSONArray())
                    .toString()
            }
            val roots = linkedSetOf<String>()
            if (dir.isNotBlank()) roots += dir.trim()
            // Common lab locations
            roots += "/storage/emulated/0/Download/Local_Models"
            roots += "/storage/emulated/0/Download"
            roots += "/sdcard/Download/Local_Models"
            roots += "/sdcard/Download"
            try {
                act?.getExternalFilesDir(null)?.let { roots += File(it, "models").absolutePath }
                act?.getExternalFilesDir(null)?.absolutePath?.let { roots += it }
            } catch (_: Throwable) {}

            val seen = HashSet<String>()
            val models = JSONArray()
            val scanned = JSONArray()
            for (root in roots) {
                scanned.put(root)
                for (m in engine.listModels(root)) {
                    val path = m.optString("path")
                    if (path.isNotEmpty() && seen.add(path)) models.put(m)
                }
            }
            JSONObject()
                .put("ok", true)
                .put("models", models)
                .put("scanned", scanned)
                .put("count", models.length())
                .toString()
        }
    }

    @JavascriptInterface
    fun requestStorageAccess(): String {
        val act = activityProvider()
        return if (act == null) {
            JSONObject().put("ok", false).put("error", "NO_ACTIVITY").toString()
        } else {
            val granted = act.hasAllFilesAccess()
            if (!granted) main.post { act.ensureAllFilesAccess() }
            JSONObject().put("ok", granted).put("hasAccess", granted).toString()
        }
    }

    @JavascriptInterface
    fun defaultModelDirs(): String {
        val arr = JSONArray()
        arr.put("/storage/emulated/0/Download/Local_Models")
        arr.put("/storage/emulated/0/Download")
        activityProvider()?.getExternalFilesDir(null)?.let {
            arr.put(File(it, "models").absolutePath)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun query(sql: String): String {
        // Read-only SELECT only — refuse anything that mutates.
        val trimmed = sql.trimStart().lowercase()
        if (!trimmed.startsWith("select") && !trimmed.startsWith("with")) {
            return JSONObject().put("error", "only SELECT/WITH allowed").toString()
        }
        return scope.launchBlocking {
            val arr = JSONArray()
            engine.query(sql).forEach { arr.put(it) }
            arr.toString()
        }
    }

    @JavascriptInterface
    fun getEngineState(): String {
        // Cheap snapshot; detailed state rides on the event stream.
        return JSONObject().put("ok", true).toString()
    }

    // ---- long ops (fire-and-forget; results arrive as events + optional callback id) -----------------

    @JavascriptInterface
    fun startRun(cfgJson: String, suiteName: String?): String {
        val reqId = nextId()
        scope.launch {
            try {
                val cfg = LaunchConfig.fromJson(JSONObject(cfgJson))
                val result = engine.startRun(cfg, suiteName)
                val j = when (result) {
                    is RunStart.Started -> JSONObject()
                        .put("ok", true)
                        .put("runId", result.runId)
                        .put("modelId", result.modelId)
                        .put("pid", result.pid)
                        .put("loadMs", result.loadMs)
                        .put("buildInfo", result.buildInfo)
                        .put("preflight", JSONObject()
                            .put("verdict", result.preflight.verdict.name)
                            .put("anonNeedMb", result.preflight.anonNeedMb)
                            .put("residentNeedMb", result.preflight.residentNeedMb)
                            .put("budgetMb", result.preflight.budgetMb)
                            .put("note", result.preflight.note))
                    is RunStart.Failed -> JSONObject()
                        .put("ok", false)
                        .put("runId", result.runId)
                        .put("reason", result.reason)
                        .put("detail", result.detail)
                }
                postEvent(JSONObject().put("type", "StartRunResult").put("reqId", reqId).put("result", j))
            } catch (t: Throwable) {
                Log.e(TAG, "startRun", t)
                postEvent(JSONObject().put("type", "StartRunResult").put("reqId", reqId)
                    .put("result", JSONObject().put("ok", false).put("reason", t.message)))
            }
        }
        return JSONObject().put("reqId", reqId).toString()
    }

    @JavascriptInterface
    fun stopRun(): String {
        val reqId = nextId()
        scope.launch {
            try {
                engine.stopRun("UI_STOP")
                postEvent(JSONObject().put("type", "StopRunResult").put("reqId", reqId).put("ok", true))
            } catch (t: Throwable) {
                postEvent(JSONObject().put("type", "StopRunResult").put("reqId", reqId)
                    .put("ok", false).put("error", t.message))
            }
        }
        return JSONObject().put("reqId", reqId).toString()
    }

    @JavascriptInterface
    fun runTurn(specJson: String): String {
        val reqId = nextId()
        scope.launch {
            try {
                val spec = TurnSpec.fromJson(JSONObject(specJson))
                val outcome = engine.runTurn(spec)
                postEvent(JSONObject()
                    .put("type", "RunTurnResult")
                    .put("reqId", reqId)
                    .put("outcome", outcome.toJson()))
            } catch (t: Throwable) {
                Log.e(TAG, "runTurn", t)
                postEvent(JSONObject().put("type", "RunTurnResult").put("reqId", reqId)
                    .put("error", t.message))
            }
        }
        return JSONObject().put("reqId", reqId).toString()
    }

    @JavascriptInterface
    fun applyVerdict(turnId: Long, isFailure: Boolean, failureClass: String?, message: String?): String {
        val reqId = nextId()
        scope.launch {
            try {
                val ok = engine.applyVerdict(turnId, isFailure, failureClass, message, null)
                postEvent(JSONObject().put("type", "VerdictResult").put("reqId", reqId)
                    .put("ok", ok).put("turnId", turnId))
            } catch (t: Throwable) {
                postEvent(JSONObject().put("type", "VerdictResult").put("reqId", reqId)
                    .put("ok", false).put("error", t.message))
            }
        }
        return JSONObject().put("reqId", reqId).toString()
    }

    @JavascriptInterface
    fun setPolicy(policyJson: String): String {
        return try {
            val j = JSONObject(policyJson)
            engine.setPolicy(
                EnginePolicy(
                    preemptiveKillHeadroomMb = j.optDouble("preemptiveKillHeadroomMb", 200.0).toFloat(),
                    autoRespawn = j.optBoolean("autoRespawn", true),
                    maxRespawns = j.optInt("maxRespawns", 3),
                    respawnBackoffMs = j.optLong("respawnBackoffMs", 2_000),
                    vitalsPeriodMs = j.optLong("vitalsPeriodMs", 200),
                    heartbeatMs = j.optLong("heartbeatMs", 1_000),
                    cooldownBatteryC = j.optDouble("cooldownBatteryC", 37.0).toFloat(),
                    cooldownMaxThermal = j.optInt("cooldownMaxThermal", 1),
                    cooldownTimeoutMs = j.optLong("cooldownTimeoutMs", 600_000),
                )
            )
            JSONObject().put("ok", true).toString()
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message).toString()
        }
    }

    // ---- event delivery ------------------------------------------------------------------------------

    private fun postEvent(j: JSONObject) {
        // Pass JSON via JSON.parse of a double-quoted string so model tokens containing
        // quotes, newlines, or U+2028/U+2029 cannot break out of the JS literal.
        val payload = JSONObject.quote(j.toString())
        main.post {
            val wv = webViewProvider() ?: return@post
            wv.evaluateJavascript(
                "window.__dyno_onEvent && window.__dyno_onEvent(JSON.parse($payload))",
                null,
            )
        }
    }

    private fun serializeEvent(ev: DynoEvent): JSONObject = when (ev) {
        is DynoEvent.EngineState -> JSONObject().put("type", "EngineState")
            .put("state", ev.state).put("pid", ev.pid)
        is DynoEvent.EngineLog -> JSONObject().put("type", "EngineLog")
            .put("line", ev.line).put("signal", ev.signal)
        is DynoEvent.RunStatus -> JSONObject().put("type", "RunStatus")
            .put("runId", ev.runId).put("status", ev.status).put("detail", ev.detail)
        is DynoEvent.TurnStarted -> JSONObject().put("type", "TurnStarted")
            .put("turnId", ev.turnId).put("turnIndex", ev.turnIndex).put("caseId", ev.caseId)
        is DynoEvent.Token -> JSONObject().put("type", "Token")
            .put("turnId", ev.turnId).put("text", ev.text)
        is DynoEvent.VitalsTick -> JSONObject().put("type", "VitalsTick").put("v", vitalsJson(ev.v))
        is DynoEvent.BreakerTrip -> JSONObject().put("type", "BreakerTrip")
            .put("turnId", ev.turnId).put("kind", ev.kind).put("detail", ev.detail)
        is DynoEvent.TurnFinished -> JSONObject().put("type", "TurnFinished")
            .put("outcome", ev.outcome.toJson())
        is DynoEvent.Cooldown -> JSONObject().put("type", "Cooldown")
            .put("batteryC", ev.batteryC).put("thermalStatus", ev.thermalStatus)
            .put("waitedMs", ev.waitedMs).put("done", ev.done)
        is DynoEvent.Recovery -> JSONObject().put("type", "Recovery")
            .put("runsMarkedCrashed", ev.summary.runsMarkedCrashed)
            .put("turnsRecovered", ev.summary.turnsRecovered)
            .put("turnsUnjudged", ev.summary.turnsUnjudged)
            .put("orphansKilled", ev.summary.orphansKilled)
        is DynoEvent.Notice -> JSONObject().put("type", "Notice")
            .put("level", ev.level).put("message", ev.message)
    }

    private fun vitalsJson(v: Vitals) = JSONObject().apply {
        put("rssMb", v.rssMb); put("rssAnonMb", v.rssAnonMb); put("hwmMb", v.hwmMb)
        put("memAvailMb", v.memAvailMb); put("lmkHeadroomMb", v.lmkHeadroomMb)
        put("batteryTempC", v.batteryTempC); put("batteryPct", v.batteryPct)
        put("thermalStatus", v.thermalStatus); put("cpuFreqCapRatio", v.cpuFreqCapRatio)
        put("throttling", v.throttling); put("lowMemory", v.lowMemory)
    }

    private var seq = 0L
    private fun nextId(): String = "r${System.currentTimeMillis()}-${++seq}"

    /**
     * Tiny helper for the few @JavascriptInterface methods that must return a value synchronously.
     * Prefer the async reqId pattern for anything that can take > ~50 ms.
     */
    private fun <T> CoroutineScope.launchBlocking(block: suspend () -> T): T {
        // WebView binder threads can block briefly; we still hop off the main looper.
        return kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
    }

    companion object {
        private const val TAG = "NativeBridge"
    }
}
