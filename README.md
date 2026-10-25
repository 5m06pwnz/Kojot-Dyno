# Kojot Dyno

On-device GGUF stress lab, LMK-aware process supervisor, and Socratic SFT dataset flywheel for Android 14 (arm64).

## Requirements

- Android Studio Hedgehog+ / AGP 8.5 / JDK 17
- Device: arm64, Android 9+ (tested target Android 14, 12 GB RAM class)
- `libllama_server.so` in `app/src/main/jniLibs/arm64-v8a/` (see that folder’s README)

## Build

```bash
./gradlew :app:assembleDebug
# or open the folder in Android Studio and Run
```

If the Gradle wrapper JAR is missing, generate it once:

```bash
gradle wrapper --gradle-version 8.7
```

## First run

1. Grant notification (and storage on API ≤32) permissions.
2. Put GGUF files under `/storage/emulated/0/Download/Local_Models`.
3. Open the app → **Scan** → pick a model → **Start Run** → **Run Suite**.

## Architecture (package `com.kojot.dyno`)

| Module | Responsibility |
|--------|----------------|
| `LlamaServerProcess` | exec `libllama_server.so` from `nativeLibraryDir`, port lifecycle, orphan reap |
| `Preflight` / `GgufMeta` | GGUF fast-path + anon vs file-backed memory model |
| `VitalsSampler` | RSS / LMK headroom / thermal / battery at ~200 ms |
| `CircuitBreaker` | TTFT / stall / wall / cyclic n-gram loop detection |
| `DynoDb` + `DynoDao` | WAL SQLite, STARTED-before-request durability, crash autopsy |
| `DynoEngine` | Run/turn orchestration |
| `DynoService` | Foreground service + wake lock |
| `NativeBridge` + SPA | WebView dashboard |
| `DatasetExporter` | Failure clusters → Socratic / ReAct JSONL |

See `SETUP.md` for the three Android 14 / Samsung gotchas (W^X, cleartext loopback, Knox SELinux).

## Export SFT data

From a debug shell or a future bridge method:

```kotlin
val exporter = DatasetExporter(context, engine.dao, /* DynoDb instance */)
val result = exporter.exportSocraticJsonl()
// result.path → JSONL under app external files
```

## GitHub Actions (APK CI)

Workflow: `.github/workflows/android.yml`

| Trigger | What it builds |
|---------|----------------|
| Push / PR to `main` or `master` | **debug** APK |
| **Actions → Build APK → Run workflow** | debug or **release** (unsigned) |

Artifacts appear under the workflow run → **Artifacts** (`kojot-dyno-debug` or `kojot-dyno-release-unsigned`).

### Optional: signed release

1. Create a keystore and base64-encode it: `base64 -w0 keystore.jks`
2. Repo **Settings → Secrets and variables → Actions**:
   - `KEYSTORE_BASE64`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
3. **Settings → Variables**: `ENABLE_SIGNED_RELEASE` = `true`
4. Run workflow with build type **release**

### Optional: ship `libllama_server.so` in CI

Without the native binary the APK still builds; the app reports `BINARY_MISSING` until the `.so` is present. To embed it in CI builds, either:

- Commit `app/src/main/jniLibs/arm64-v8a/libllama_server.so` (large; use Git LFS), or
- Uncomment the **Fetch libllama_server.so** step and set secret `LLAMA_SERVER_SO_URL` to a direct download URL.
