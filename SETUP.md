# Kojot Dyno — Integration Notes (Android 14 / Samsung)

## Gotchas already wired in the engine

### 1. W^X / native binary packaging
`LlamaServerProcess` executes `libllama_server.so` from `applicationInfo.nativeLibraryDir`.

**Required in `AndroidManifest.xml` `<application>`:**
```xml
android:extractNativeLibs="true"
```
Ship the binary as `src/main/jniLibs/arm64-v8a/libllama_server.so` (it must be a real ELF, not a script).

Without `extractNativeLibs=true`, Android mounts the library from the compressed APK and `execve` fails → `BINARY_MISSING`.

### 2. Cleartext loopback
`InferenceClient` uses `http://127.0.0.1:8080`. Copy `network_security_config.xml` to `res/xml/` and reference it:
```xml
android:networkSecurityConfig="@xml/network_security_config"
```

### 3. Samsung Knox / SELinux
- `/proc/$pid/clear_refs` writes are best-effort; failures are swallowed. Peak RSS falls back to sampled peak.
- CPU frequency sysfs nodes are often blocked; `cpuFreqCapRatio` may stay `null`. Battery temp + `PowerManager.currentThermalStatus` still work.

## Wiring the WebView

```kotlin
class MainActivity : AppCompatActivity() {
    private var service: DynoService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val svc = (b as DynoService.LocalBinder).getService()
            service = svc
            val bridge = NativeBridge(
                engine = svc.engine,
                scope = lifecycleScope,
                webViewProvider = { webView },
                onNotificationText = { svc.updateNotification(it) },
            )
            bridge.bindEvents()
            webView.addJavascriptInterface(bridge, "Dyno")
            webView.loadUrl("file:///android_asset/index.html")
        }
        override fun onServiceDisconnected(n: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... set up WebView with JS enabled, DOM storage, etc.
        DynoService.start(this)
        bindService(Intent(this, DynoService::class.java), conn, Context.BIND_AUTO_CREATE)
    }
}
```

Place `index.html`, `style.css`, `dyno_app.js` under `src/main/assets/`.
Place `dyno_schema.sql` and `dyno_views.sql` under `src/main/assets/` (DynoDb loads them by name).

## File layout (Kotlin package `com.kojot.dyno`)

```
CircuitBreaker.kt
DynoDb.kt          # DynoDb + DbWriter + DynoDao
DynoEngine.kt      # orchestrator
DynoService.kt     # foreground service
DynoTypes.kt
GgufMeta.kt        # + Preflight
InferenceClient.kt
LlamaServerProcess.kt
NativeBridge.kt
VitalsSampler.kt
```

## Next: Flywheel exporter
See `DatasetExporter.kt` for the Socratic multi-turn JSONL synthesis path.
