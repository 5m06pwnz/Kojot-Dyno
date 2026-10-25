package com.kojot.dyno

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

/**
 * Host activity: binds [DynoService], injects [NativeBridge] as `window.Dyno`, loads the SPA from assets.
 *
 * Storage: Android 11+ blocks arbitrary /Download reads without MANAGE_EXTERNAL_STORAGE.
 * We request all-files access (sideload / lab tool) so Scan can see GGUFs under Download.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var service: DynoService? = null
    private var bridge: NativeBridge? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as DynoService.LocalBinder).getService()
            service = svc
            val b = NativeBridge(
                engine = svc.engine,
                scope = lifecycleScope,
                webViewProvider = { if (::webView.isInitialized) webView else null },
                onNotificationText = { svc.updateNotification(it) },
                activityProvider = { this@MainActivity },
            )
            b.bindEvents()
            bridge = b
            webView.addJavascriptInterface(b, "Dyno")
            // Load SPA only after the bridge exists so Scan works on first paint
            webView.loadUrl("file:///android_asset/index.html")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bridge = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results inspected on next Scan */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).also { setContentView(it) }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        // Placeholder until service connects
        webView.loadData(
            "<html><body style='background:#0b0d10;color:#8b97a8;font-family:sans-serif;padding:24px'>" +
                "Starting Dyno engine…</body></html>",
            "text/html",
            "utf-8",
        )

        requestRuntimePermissions()
        DynoService.start(this)
        bindService(Intent(this, DynoService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= 32) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())

        // Android 11+: full filesystem access for GGUF paths under /Download
        ensureAllFilesAccess()
    }

    /** Opens system “All files access” screen once if not already granted. */
    fun ensureAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        if (Environment.isExternalStorageManager()) return true
        return try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Allow “All files access” so Dyno can scan Download/Local_Models",
                Toast.LENGTH_LONG,
            ).show()
            false
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {}
            false
        }
    }

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    override fun onResume() {
        super.onResume()
        // If user just granted all-files, nothing else to do — next Scan will work
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Throwable) {}
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Dyno")
            webView.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
