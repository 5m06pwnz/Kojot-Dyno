/* Kojot Dyno — SPA test harness. Talks to NativeBridge via window.Dyno. */
(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const native = () => window.Dyno; // injected by Android WebView.addJavascriptInterface

  // ---- collapsible cards ----
  document.querySelectorAll(".card.collapsible").forEach((card) => {
    card.querySelector(".card-h").addEventListener("click", () => {
      card.classList.toggle("open");
    });
  });

  // ---- state ----
  let currentModel = null;
  let currentRunId = null;
  let suiteRunning = false;
  const headroomHist = [];

  // ---- event bus from native ----
  window.__dyno_onEvent = function (jsonStr) {
    let ev;
    try { ev = JSON.parse(jsonStr); } catch (e) { console.warn("bad event", e); return; }
    handleEvent(ev);
  };

  function handleEvent(ev) {
    switch (ev.type) {
      case "EngineState":
        setPill(ev.state);
        $("pid-label").textContent = ev.pid ? `pid ${ev.pid}` : "";
        break;
      case "EngineLog":
        appendLog($("engine-log"), ev.line + (ev.signal ? `  [${ev.signal}]` : ""));
        break;
      case "RunStatus":
        currentRunId = ev.runId;
        setPill(ev.status);
        $("btn-stop").disabled = !(ev.status === "READY" || ev.status === "RUNNING");
        $("btn-run-suite").disabled = ev.status !== "READY" && ev.status !== "RUNNING";
        if (ev.status === "CRASHED" || ev.status === "STOPPED" || ev.status === "FAILED") {
          suiteRunning = false;
        }
        break;
      case "TurnStarted":
        appendStream(`\n── turn #${ev.turnIndex} ${ev.caseId} ──\n`);
        break;
      case "Token":
        appendStream(ev.text);
        break;
      case "VitalsTick":
        paintVitals(ev.v);
        break;
      case "BreakerTrip":
        appendStream(`\n⚠ breaker ${ev.kind}: ${JSON.stringify(ev.detail)}\n`);
        break;
      case "TurnFinished":
        appendStream(`\n← ${ev.outcome.isFailure ? "FAIL " + (ev.outcome.failureClass || "") : "ok"}` +
          `  ttft=${fmt(ev.outcome.ttftMs)}ms  tps=${fmt(ev.outcome.tokPerSec)}\n`);
        break;
      case "Cooldown":
        appendStream(ev.done
          ? `\n(cooldown done after ${ev.waitedMs}ms)\n`
          : `\n(cooldown… batt=${fmt(ev.batteryC)}°C thermal=${ev.thermalStatus})\n`);
        break;
      case "Recovery":
        appendLog($("engine-log"),
          `recovery: runs=${ev.runsMarkedCrashed} turns=${ev.turnsRecovered} unjudged=${ev.turnsUnjudged} orphans=${ev.orphansKilled}`);
        break;
      case "Notice":
        appendLog($("engine-log"), `[${ev.level}] ${ev.message}`);
        break;
      case "StartRunResult":
        if (ev.result && ev.result.ok) {
          currentRunId = ev.result.runId;
          $("preflight-note").textContent = ev.result.preflight
            ? `${ev.result.preflight.verdict}: ${ev.result.preflight.note}`
            : "";
          $("btn-stop").disabled = false;
          $("btn-run-suite").disabled = false;
        } else {
          $("preflight-note").textContent = `START FAILED: ${(ev.result && (ev.result.reason + " " + (ev.result.detail || ""))) || "unknown"}`;
        }
        break;
      case "StopRunResult":
        $("btn-stop").disabled = true;
        $("btn-run-suite").disabled = true;
        suiteRunning = false;
        break;
      case "RunTurnResult":
        // individual turn results are also emitted as TurnFinished
        break;
      default:
        break;
    }
  }

  function setPill(state) {
    const el = $("engine-pill");
    el.textContent = state || "—";
    el.className = "pill " + String(state || "stopped").toLowerCase();
  }

  function paintVitals(v) {
    $("v-rss").textContent = fmtMb(v.rssMb);
    $("v-anon").textContent = fmtMb(v.rssAnonMb);
    $("v-avail").textContent = fmtMb(v.memAvailMb);
    $("v-headroom").textContent = fmtMb(v.lmkHeadroomMb);
    $("v-batt").textContent = v.batteryTempC != null ? `${fmt(v.batteryTempC)}°C ${v.batteryPct ?? ""}%` : "—";
    $("v-thermal").textContent = thermalName(v.thermalStatus) + (v.throttling ? " 🔥" : "");
    if (typeof v.lmkHeadroomMb === "number") {
      headroomHist.push(v.lmkHeadroomMb);
      if (headroomHist.length > 120) headroomHist.shift();
      drawSpark(headroomHist);
    }
  }

  function thermalName(s) {
    return ["NONE", "LIGHT", "MOD", "SEVERE", "CRIT", "EMERG", "SHUTDOWN"][s] || String(s);
  }

  function drawSpark(data) {
    const c = $("spark");
    if (!c) return;
    const ctx = c.getContext("2d");
    const w = c.width, h = c.height;
    ctx.clearRect(0, 0, w, h);
    if (data.length < 2) return;
    const min = Math.min(...data), max = Math.max(...data);
    const span = Math.max(1, max - min);
    ctx.strokeStyle = "#3d9cf0";
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    data.forEach((v, i) => {
      const x = (i / (data.length - 1)) * (w - 2) + 1;
      const y = h - 2 - ((v - min) / span) * (h - 4);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }

  function appendStream(t) {
    const el = $("stream-out");
    el.textContent += t;
    el.scrollTop = el.scrollHeight;
  }
  function appendLog(el, line) {
    el.textContent += line + "\n";
    el.scrollTop = el.scrollHeight;
  }
  function fmt(n) { return n == null || Number.isNaN(n) ? "—" : Number(n).toFixed(1); }
  function fmtMb(n) { return n == null ? "—" : `${Number(n).toFixed(0)} MB`; }

  // ---- UI actions ----
  $("btn-scan").addEventListener("click", () => {
    if (!native()) {
      $("model-meta").textContent = "Native bridge not available — is the Dyno service running?";
      return alert("Native bridge not available");
    }
    const dir = $("model-dir").value;
    let raw;
    try { raw = native().listModels(dir); } catch (e) { return alert(String(e)); }
    let payload;
    try { payload = JSON.parse(raw); } catch (e) { return alert("Bad scan response: " + raw); }

    // New shape: { ok, models, error?, message?, scanned? }
    // Legacy: bare array
    const list = Array.isArray(payload) ? payload : (payload.models || []);
    const sel = $("model-list");
    sel.innerHTML = "";
    currentModel = null;

    if (!Array.isArray(payload) && payload.ok === false) {
      $("model-meta").textContent = payload.message || payload.error || "Scan failed";
      if (payload.error === "ALL_FILES_REQUIRED" && native().requestStorageAccess) {
        try { native().requestStorageAccess(); } catch (_) {}
      }
      appendLog($("engine-log"), "scan: " + (payload.message || payload.error));
      return;
    }

    list.forEach((m, i) => {
      const opt = document.createElement("option");
      opt.value = i;
      const gb = m.sizeBytes != null ? (m.sizeBytes / 1e9).toFixed(2) + " GB" : "?";
      opt.textContent = `${m.name}  (${gb}${m.quant ? ", " + m.quant : ""})`;
      opt._meta = m;
      sel.appendChild(opt);
    });
    if (list.length) {
      sel.selectedIndex = 0;
      currentModel = list[0];
      showMeta(currentModel);
      appendLog($("engine-log"), "scan: " + list.length + " model(s)");
    } else {
      const scanned = (!Array.isArray(payload) && payload.scanned)
        ? payload.scanned.join(", ")
        : dir;
      $("model-meta").textContent =
        "No .gguf found. Put models in Download/Local_Models (or Download), grant All files access, then Scan again. Looked in: " + scanned;
      appendLog($("engine-log"), "scan: 0 models in " + scanned);
    }
  });

  $("model-list").addEventListener("change", (e) => {
    const opt = e.target.selectedOptions[0];
    currentModel = opt && opt._meta;
    showMeta(currentModel);
  });

  function showMeta(m) {
    if (!m) { $("model-meta").textContent = ""; return; }
    $("model-meta").textContent =
      `${m.family || "?"} · ${m.paramSizeB || "?"}B · ${m.quant || "?"} · ctx_train=${m.nCtxTrain || "?"} · ${m.path}`;
  }

  $("btn-start").addEventListener("click", () => {
    if (!native() || !currentModel) return alert("Scan and pick a model first");
    const cfg = {
      modelPath: currentModel.path,
      ctxSize: +$("cfg-ctx").value,
      threads: +$("cfg-threads").value,
      batchSize: +$("cfg-batch").value,
      ubatchSize: +$("cfg-ubatch").value,
      cacheTypeK: $("cfg-ck").value,
      cacheTypeV: $("cfg-cv").value,
      mmap: $("cfg-mmap").checked,
      mlock: $("cfg-mlock").checked,
      preflight: $("cfg-preflight").value,
      port: 8080,
    };
    $("preflight-note").textContent = "starting…";
    $("stream-out").textContent = "";
    native().startRun(JSON.stringify(cfg), $("cfg-suite").value || "adhoc");
  });

  $("btn-stop").addEventListener("click", () => {
    if (!native()) return;
    suiteRunning = false;
    native().stopRun();
  });

  $("btn-run-suite").addEventListener("click", async () => {
    if (!native() || suiteRunning) return;
    suiteRunning = true;
    const name = $("suite-pick").value;
    const cases = buildSuite(name);
    for (const c of cases) {
      if (!suiteRunning) break;
      const body = {
        model: "local",
        messages: [{ role: "user", content: c.prompt }],
        temperature: +$("s-temp").value,
        min_p: +$("s-minp").value,
        max_tokens: +$("s-npred").value,
      };
      if (c.grammar) body.grammar = c.grammar;
      const spec = {
        caseId: c.id,
        body,
        grammarMode: c.grammar ? "GBNF" : "NONE",
        caseParams: c.params || null,
        autoComplete: false,
        loopDetection: true,
        stallMs: 45000,
        wallDeadlineMs: 300000,
      };
      // fire turn; wait for TurnFinished via a one-shot promise
      const done = waitForTurn();
      native().runTurn(JSON.stringify(spec));
      const outcome = await done;
      // deterministic schema check when expected
      if (c.expectJson && outcome && outcome.output != null) {
        const verdict = validateJson(outcome.output, c.schema);
        native().applyVerdict(
          outcome.turnId,
          !verdict.ok,
          verdict.ok ? null : (verdict.failureClass || "SCHEMA_SYNTAX_ERROR"),
          verdict.message || null
        );
      } else if (outcome && !outcome.isFailure) {
        native().applyVerdict(outcome.turnId, false, null, null);
      }
    }
    suiteRunning = false;
  });

  function waitForTurn() {
    return new Promise((resolve) => {
      const prev = window.__dyno_onEvent;
      window.__dyno_onEvent = function (jsonStr) {
        let ev;
        try { ev = JSON.parse(jsonStr); } catch (_) { return; }
        handleEvent(ev);
        if (ev.type === "TurnFinished") {
          window.__dyno_onEvent = prev;
          resolve(ev.outcome);
        } else if (ev.type === "RunStatus" && (ev.status === "CRASHED" || ev.status === "STOPPED")) {
          window.__dyno_onEvent = prev;
          resolve(null);
        }
      };
    });
  }

  function validateJson(text, schema) {
    const cleaned = stripThink(text).trim();
    // extract first JSON object/array if model wrapped it
    const m = cleaned.match(/(\{[\s\S]*\}|\[[\s\S]*\])/);
    const candidate = m ? m[1] : cleaned;
    let obj;
    try { obj = JSON.parse(candidate); }
    catch (e) {
      return { ok: false, failureClass: "SCHEMA_SYNTAX_ERROR", message: String(e.message || e) };
    }
    if (!schema) return { ok: true };
    // minimal required-keys check
    if (schema.required) {
      for (const k of schema.required) {
        if (obj == null || !(k in obj)) {
          return { ok: false, failureClass: "SCHEMA_VIOLATION", message: `missing key: ${k}` };
        }
      }
    }
    return { ok: true };
  }

  function stripThink(s) {
    return s.replace(/<think>[\s\S]*?<\/think>/gi, "").trim();
  }

  // ---- built-in suites (expandable) ----
  function buildSuite(name) {
    if (name === "smoke") {
      return [{ id: "smoke_hello", prompt: "Reply with a single word: pong", expectJson: false }];
    }
    if (name === "schema_gauntlet") {
      const schema = { required: ["name", "age", "tags"] };
      return [
        {
          id: "schema_flat",
          prompt: 'Return ONLY JSON: {"name":"Ada","age":36,"tags":["math","code"]}',
          expectJson: true, schema, params: { depth: 1 },
        },
        {
          id: "schema_nested",
          prompt: 'Return ONLY JSON with shape {"name":str,"age":int,"tags":[str],"address":{"city":str,"zip":str}} for a fictional person.',
          expectJson: true, schema: { required: ["name", "age", "tags", "address"] }, params: { depth: 2 },
        },
        {
          id: "schema_unterminated_bait",
          prompt: 'Start a JSON object with key "note" whose value is a long string about memory pressure, but make sure the JSON is complete and valid.',
          expectJson: true, schema: { required: ["note"] }, params: { trap: "unterminated" },
        },
      ];
    }
    if (name === "sampler_sweep") {
      // single case here; real sweep would vary body.temperature / min_p per turn from the UI knobs
      return [{
        id: "sampler_loop_probe",
        prompt: "Write a 40-word paragraph about recursive functions, then stop.",
        expectJson: false, params: { temp: +$("s-temp").value, min_p: +$("s-minp").value },
      }];
    }
    if (name === "context_ladder") {
      return [1, 2, 4].map((k) => {
        const needle = `SECRET_${k}K_${Math.random().toString(36).slice(2, 8)}`;
        const hay = ("lorem ipsum dolor sit amet ".repeat(80 * k)) + `\nThe secret code is ${needle}.\n` + ("consectetur adipiscing elit ".repeat(80 * k));
        return {
          id: `needle_${k}k`,
          prompt: hay + "\nWhat is the secret code? Reply with only the code.",
          expectJson: false, params: { depth_k: k, needle },
        };
      });
    }
    if (name === "agent_tool_chaos") {
      return [{
        id: "tool_arg_drift",
        prompt: "You have a tool `lookup(id: int)`. Call it three times with different ids. Use the exact format: tool call lookup with id=<n>",
        expectJson: false, params: { fault: "none" },
      }];
    }
    return [];
  }

  $("btn-refresh-stats").addEventListener("click", () => {
    if (!native()) return;
    const sql = `
      SELECT failure_class, COUNT(*) AS n
      FROM telemetry_turns
      WHERE is_failure=1 AND turn_state IN ('COMPLETE','RECOVERED_CRASH')
      GROUP BY failure_class
      ORDER BY n DESC
      LIMIT 20`;
    try {
      const rows = JSON.parse(native().query(sql));
      $("stats-out").textContent = rows.map((r) => `${String(r.n).padStart(4)}  ${r.failure_class}`).join("\n") || "(no failures yet)";
    } catch (e) {
      $("stats-out").textContent = String(e);
    }
  });

  // auto-scan on load if bridge present
  setTimeout(() => {
    if (native()) {
      try { $("btn-scan").click(); } catch (_) {}
    } else {
      appendLog($("engine-log"), "Native bridge missing — waiting for Dyno service…");
      // retry a few times while the service binds
      let tries = 0;
      const t = setInterval(() => {
        tries++;
        if (native()) { clearInterval(t); try { $("btn-scan").click(); } catch (_) {} }
        else if (tries > 20) { clearInterval(t); appendLog($("engine-log"), "Bridge never appeared."); }
      }, 500);
    }
  }, 400);
})();
